#!/bin/bash
# Quick start script for Druid with Consul

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Druid with Consul - Quick Start ==="
echo ""

# Check prerequisites
echo "1. Checking prerequisites..."
if ! command -v docker &> /dev/null; then
    echo "   ❌ Docker not found. Please install Docker."
    exit 1
fi
echo "   ✅ Docker found"

if ! command -v docker-compose &> /dev/null; then
    echo "   ❌ Docker Compose not found. Please install Docker Compose."
    exit 1
fi
echo "   ✅ Docker Compose found"

# Check if ports are available
echo ""
echo "2. Checking if required ports are available..."
for port in 8081 8082 8083 8090 8091 8500 8888; do
    if lsof -Pi :$port -sTCP:LISTEN -t >/dev/null 2>&1 ; then
        echo "   ⚠️  Port $port is already in use. Please free it or stop conflicting services."
        exit 1
    fi
done
echo "   ✅ All required ports are available"
echo "   (PostgreSQL will use port 5433 to avoid conflicts)"

# Start the cluster
echo ""
echo "3. Starting Druid cluster with Consul..."
docker-compose up -d

echo ""
echo "4. Waiting for services to be healthy..."
echo "   This may take 1-2 minutes..."
sleep 30

# Check service health
echo ""
echo "5. Checking service health..."
services=("consul" "postgres" "coordinator" "overlord" "broker" "historical" "middlemanager" "router")
for service in "${services[@]}"; do
    if docker-compose ps -q "$service" &> /dev/null; then
        status=$(docker-compose ps "$service" | tail -1 | awk '{print $4}')
        if [[ "$status" == *"Up"* ]]; then
            echo "   ✅ $service is running"
        else
            echo "   ⚠️  $service status: $status"
        fi
    else
        echo "   ❌ $service not found"
    fi
done

# Wait for Router to be ready
echo ""
echo "6. Waiting for Router API to be ready..."
max_wait=120
waited=0
while ! curl -s http://localhost:8888/status/health > /dev/null 2>&1; do
    if [ $waited -ge $max_wait ]; then
        echo "   ❌ Timeout waiting for Router. Check logs with: docker-compose logs router"
        exit 1
    fi
    echo "   Waiting... ($waited/$max_wait seconds)"
    sleep 5
    waited=$((waited + 5))
done
echo "   ✅ Router is ready"

# Check Consul registration
echo ""
echo "7. Verifying Consul registration..."
sleep 5
services_count=$(curl -s http://localhost:8500/v1/catalog/services | jq 'keys[] | select(startswith("druid"))' | wc -l)
echo "   Registered Druid services in Consul: $services_count"
if [ "$services_count" -lt 5 ]; then
    echo "   ⚠️  Expected at least 5 services. Waiting a bit more..."
    sleep 10
    services_count=$(curl -s http://localhost:8500/v1/catalog/services | jq 'keys[] | select(startswith("druid"))' | wc -l)
    echo "   Registered services: $services_count"
fi

# Show leader election
echo ""
echo "8. Checking leader election..."
echo "   Coordinator leader:"
coordinator_leader=$(curl -s http://localhost:8500/v1/kv/druid/leader/coordinator?raw 2>/dev/null || echo "Not elected yet")
echo "   $coordinator_leader"

echo "   Overlord leader:"
overlord_leader=$(curl -s http://localhost:8500/v1/kv/druid/leader/overlord?raw 2>/dev/null || echo "Not elected yet")
echo "   $overlord_leader"

echo ""
echo "=== ✅ Cluster is ready! ==="
echo ""
echo "Access points:"
echo "  - Consul UI:         http://localhost:8500"
echo "  - Druid Router:      http://localhost:8888"
echo "  - Coordinator UI:    http://localhost:8081"
echo "  - Overlord UI:       http://localhost:8090"
echo ""
echo "Next steps:"
echo "  1. Load sample data and run tests:"
echo "     ./test-cluster.sh"
echo ""
echo "  2. View Consul service catalog:"
echo "     open http://localhost:8500/ui/dc1/services"
echo ""
echo "  3. View cluster logs:"
echo "     docker-compose logs -f"
echo ""
echo "  4. Stop cluster:"
echo "     docker-compose down"
echo ""
echo "  5. Stop and remove all data:"
echo "     docker-compose down -v"
echo ""
