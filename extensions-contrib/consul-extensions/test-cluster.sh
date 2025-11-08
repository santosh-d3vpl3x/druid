#!/bin/bash
# Test script for Consul-based Druid cluster
# This script loads data, performs segment operations, and queries

set -e

ROUTER_URL="http://localhost:8888"
COORDINATOR_URL="http://localhost:8081"
OVERLORD_URL="http://localhost:8090"

echo "=== Druid Consul Cluster Test Script ==="
echo ""

# Wait for services to be ready
echo "1. Waiting for services to be ready..."
echo "   Checking Router..."
until curl -s "${ROUTER_URL}/status/health" > /dev/null 2>&1; do
  echo "   Waiting for Router to be ready..."
  sleep 5
done
echo "   ✅ Router is ready"

echo "   Checking Coordinator..."
until curl -s "${COORDINATOR_URL}/status/health" > /dev/null 2>&1; do
  echo "   Waiting for Coordinator to be ready..."
  sleep 5
done
echo "   ✅ Coordinator is ready"

echo "   Checking Overlord..."
until curl -s "${OVERLORD_URL}/status/health" > /dev/null 2>&1; do
  echo "   Waiting for Overlord to be ready..."
  sleep 5
done
echo "   ✅ Overlord is ready"

# Check Consul registration
echo ""
echo "2. Checking Consul service registration..."
consul_services=$(curl -s http://localhost:8500/v1/catalog/services | jq -r 'keys[]' | grep druid || true)
if [ -z "$consul_services" ]; then
  echo "   ⚠️  No Druid services registered in Consul yet. Waiting..."
  sleep 10
  consul_services=$(curl -s http://localhost:8500/v1/catalog/services | jq -r 'keys[]' | grep druid || true)
fi
echo "   Registered services:"
echo "$consul_services" | while read service; do
  echo "   - $service"
done

# Check leader election
echo ""
echo "3. Checking leader election in Consul..."
echo "   Coordinator leader:"
curl -s http://localhost:8500/v1/kv/druid/leader/coordinator?raw 2>/dev/null || echo "   No leader elected yet"

echo "   Overlord leader:"
curl -s http://localhost:8500/v1/kv/druid/leader/overlord?raw 2>/dev/null || echo "   No leader elected yet"

# Load sample data using inline spec
echo ""
echo "4. Loading sample data (Wikipedia edits)..."
cat > /tmp/ingestion-spec.json << 'EOF'
{
  "type": "index_parallel",
  "spec": {
    "ioConfig": {
      "type": "index_parallel",
      "inputSource": {
        "type": "http",
        "uris": ["https://druid.apache.org/data/wikipedia.json.gz"]
      },
      "inputFormat": {
        "type": "json"
      }
    },
    "tuningConfig": {
      "type": "index_parallel",
      "partitionsSpec": {
        "type": "dynamic"
      }
    },
    "dataSchema": {
      "dataSource": "wikipedia",
      "timestampSpec": {
        "column": "timestamp",
        "format": "iso"
      },
      "dimensionsSpec": {
        "dimensions": [
          "channel",
          "cityName",
          "comment",
          "countryIsoCode",
          "countryName",
          "isAnonymous",
          "isMinor",
          "isNew",
          "isRobot",
          "isUnpatrolled",
          "metroCode",
          "namespace",
          "page",
          "regionIsoCode",
          "regionName",
          "user"
        ]
      },
      "metricsSpec": [
        { "name": "count", "type": "count" },
        { "name": "added", "type": "longSum", "fieldName": "added" },
        { "name": "deleted", "type": "longSum", "fieldName": "deleted" },
        { "name": "delta", "type": "longSum", "fieldName": "delta" }
      ],
      "granularitySpec": {
        "type": "uniform",
        "segmentGranularity": "day",
        "queryGranularity": "none",
        "rollup": false,
        "intervals": null
      }
    }
  }
}
EOF

task_id=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d @/tmp/ingestion-spec.json \
  "${OVERLORD_URL}/druid/indexer/v1/task" | jq -r '.task')

echo "   Submitted ingestion task: $task_id"
echo "   Waiting for task to complete..."

# Wait for task to complete
while true; do
  status=$(curl -s "${OVERLORD_URL}/druid/indexer/v1/task/${task_id}/status" | jq -r '.status.status')
  echo "   Task status: $status"
  
  if [ "$status" == "SUCCESS" ]; then
    echo "   ✅ Ingestion completed successfully"
    break
  elif [ "$status" == "FAILED" ]; then
    echo "   ❌ Ingestion failed"
    curl -s "${OVERLORD_URL}/druid/indexer/v1/task/${task_id}/reports" | jq '.'
    exit 1
  fi
  
  sleep 10
done

# Wait for segments to be loaded
echo ""
echo "5. Waiting for segments to be loaded..."
sleep 15

# Query the data
echo ""
echo "6. Querying loaded data..."
cat > /tmp/query.json << 'EOF'
{
  "queryType": "timeseries",
  "dataSource": "wikipedia",
  "granularity": "hour",
  "aggregations": [
    { "type": "count", "name": "count" },
    { "type": "longSum", "name": "total_added", "fieldName": "added" }
  ],
  "intervals": ["2015-09-12/2015-09-13"]
}
EOF

echo "   Running timeseries query..."
result=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d @/tmp/query.json \
  "${ROUTER_URL}/druid/v2")

row_count=$(echo "$result" | jq 'length')
echo "   Query returned $row_count time buckets"
echo "   Sample result:"
echo "$result" | jq '.[0]'

# Check segment metadata
echo ""
echo "7. Checking segment metadata..."
segments=$(curl -s "${COORDINATOR_URL}/druid/coordinator/v1/datasources/wikipedia/segments?full")
segment_count=$(echo "$segments" | jq 'length')
echo "   Total segments: $segment_count"
echo "   Sample segment:"
echo "$segments" | jq '.[0] | {identifier, interval, size, numRows}'

# Test SQL query
echo ""
echo "8. Running SQL query..."
cat > /tmp/sql-query.json << 'EOF'
{
  "query": "SELECT channel, COUNT(*) as edit_count, SUM(added) as total_added FROM wikipedia WHERE __time >= TIMESTAMP '2015-09-12 00:00:00' AND __time < TIMESTAMP '2015-09-13 00:00:00' GROUP BY channel ORDER BY edit_count DESC LIMIT 5",
  "context": {
    "sqlQueryId": "test-query-001"
  }
}
EOF

echo "   Top 5 channels by edit count:"
curl -s -X POST -H 'Content-Type: application/json' \
  -d @/tmp/sql-query.json \
  "${ROUTER_URL}/druid/v2/sql" | jq '.'

# Perform segment compaction
echo ""
echo "9. Testing segment compaction..."
cat > /tmp/compaction-spec.json << 'EOF'
{
  "type": "compact",
  "dataSource": "wikipedia",
  "interval": "2015-09-12/2015-09-13",
  "tuningConfig": {
    "type": "index_parallel",
    "partitionsSpec": {
      "type": "dynamic",
      "maxRowsPerSegment": 5000000
    }
  }
}
EOF

compact_task_id=$(curl -s -X POST -H 'Content-Type: application/json' \
  -d @/tmp/compaction-spec.json \
  "${OVERLORD_URL}/druid/indexer/v1/task" | jq -r '.task')

echo "   Submitted compaction task: $compact_task_id"
echo "   (Not waiting for completion - check status manually)"

# Show datasources
echo ""
echo "10. Listing all datasources..."
curl -s "${COORDINATOR_URL}/druid/coordinator/v1/datasources" | jq '.'

# Show cluster status
echo ""
echo "11. Cluster status summary..."
echo "   Servers:"
curl -s "${COORDINATOR_URL}/druid/coordinator/v1/servers?simple" | jq '.[] | {host, type, tier, currSize, maxSize}'

echo ""
echo "=== Test completed successfully! ==="
echo ""
echo "Useful endpoints:"
echo "  - Consul UI:        http://localhost:8500"
echo "  - Router:           http://localhost:8888"
echo "  - Coordinator:      http://localhost:8081"
echo "  - Overlord:         http://localhost:8090"
echo "  - Broker:           http://localhost:8082"
echo ""
echo "Check Consul for service discovery:"
echo "  curl http://localhost:8500/v1/catalog/services"
echo "  curl http://localhost:8500/v1/health/service/druid-broker"
echo ""

# Cleanup temp files
rm -f /tmp/ingestion-spec.json /tmp/query.json /tmp/sql-query.json /tmp/compaction-spec.json
