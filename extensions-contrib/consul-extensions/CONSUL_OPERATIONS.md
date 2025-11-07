<!--
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
-->

# Consul Operations Guide for Druid Integration

## Table of Contents
1. [Implementation Review](#implementation-review)
2. [Consul Cluster Architecture](#consul-cluster-architecture)
3. [Performance Tuning](#performance-tuning)
4. [ACL Configuration](#acl-configuration)
5. [Monitoring and Alerting](#monitoring-and-alerting)
6. [Capacity Planning](#capacity-planning)
7. [Network Requirements](#network-requirements)
8. [Disaster Recovery](#disaster-recovery)
9. [Troubleshooting](#troubleshooting)
10. [Production Checklist](#production-checklist)

---

## Implementation Review

### What This Extension Does

The Druid Consul extension uses three core Consul features:

1. **Service Catalog** - For node discovery
   - Each Druid node registers as a service (brokers, coordinators, historicals, etc.)
   - Service metadata stored in the `meta` field (~5-10KB per node)
   - TTL health checks updated every 10 seconds (configurable)
   - Services auto-deregister after 90 seconds of failed health checks (configurable)

2. **Blocking Queries** - For change detection
   - Uses long-polling with 60-second timeouts (configurable)
   - Each node role watcher creates one blocking query per service type
   - Queries automatically retry on errors

3. **Sessions + KV Store** - For leader election
   - Two leader locks: `/druid/leader/coordinator` and `/druid/leader/overlord`
   - Session TTL-based locking (similar to ZooKeeper ephemeral nodes)
   - Session renewal every 10 seconds (tied to healthCheckInterval)
   - Lock-delay of 5 seconds prevents rapid re-acquisition

### Consul Load Characteristics

**Per Druid Node:**
- 1 service registration
- 1 TTL health check pass every 10s
- N blocking queries (1 per node role being watched, typically 3-5)
- 0-1 session (only for Coordinator/Overlord candidates)
- 0-1 session renewal every 10s (only for leaders)

**For a 50-node Druid cluster:**
- 50 service registrations
- 50 health check updates/sec (at 10s intervals)
- ~200 blocking queries (50 nodes × 4 average roles watched)
- 2 active sessions (1 Coordinator leader + 1 Overlord leader)
- Multiple leader election attempts during startup or failover

---

## Consul Cluster Architecture

### Recommended Topology

#### Small Druid Deployment (< 20 nodes)
```
┌─────────────────────────────────────┐
│   Consul Cluster (3 servers)       │
│   - consul-01 (leader)              │
│   - consul-02 (follower)            │
│   - consul-03 (follower)            │
└─────────────────────────────────────┘
         │
         │ (direct connection)
         │
┌─────────────────────────────────────┐
│   Druid Nodes                       │
│   - Connect directly to servers     │
│   - Use load balancer DNS           │
└─────────────────────────────────────┘
```

**Configuration:**
- 3 Consul servers (minimum for HA)
- Druid nodes connect directly to Consul servers via load balancer
- No Consul client agents needed

#### Medium Druid Deployment (20-100 nodes)
```
┌─────────────────────────────────────┐
│   Consul Cluster (5 servers)       │
│   - Dedicated hardware              │
│   - SSD storage                     │
└─────────────────────────────────────┘
         │
         │ (client-server RPC)
         │
┌─────────────────────────────────────┐
│   Consul Client Agents              │
│   - Co-located with Druid nodes     │
│   - localhost connection            │
└─────────────────────────────────────┘
         │
┌─────────────────────────────────────┐
│   Druid Nodes                       │
│   - druid.discovery.consul.host=    │
│     localhost                       │
└─────────────────────────────────────┘
```

**Configuration:**
- 5 Consul servers for better read scaling
- Consul client agent on each Druid host
- Druid connects to localhost:8500
- Reduces load on Consul servers (client agents handle blocking queries locally)

#### Large Druid Deployment (100+ nodes)
```
┌──────────────────────────────────────────┐
│   Consul Cluster (5-7 servers)          │
│   - High-performance hardware            │
│   - NVMe storage                         │
│   - Dedicated network                    │
│   - Performance tuning enabled           │
└──────────────────────────────────────────┘
         │
         │ (federated via WAN)
         │
┌──────────────────────────────────────────┐
│   Per-Region Consul Client Clusters     │
│   - Client agents on each host           │
│   - Connection pooling                   │
│   - Local caching                        │
└──────────────────────────────────────────┘
```

**Configuration:**
- 5-7 Consul servers (odd number for quorum)
- Consul client agents on every Druid host
- Consider multi-datacenter federation for global deployments
- Enable connection pooling and caching

### Server Hardware Recommendations

| Cluster Size | CPU | RAM | Disk | Network |
|--------------|-----|-----|------|---------|
| < 20 nodes   | 2 cores | 4 GB | 20 GB SSD | 1 Gbps |
| 20-100 nodes | 4 cores | 8 GB | 50 GB SSD | 10 Gbps |
| 100-500 nodes | 8 cores | 16 GB | 100 GB NVMe | 10 Gbps |
| 500+ nodes   | 16 cores | 32 GB | 200 GB NVMe | 10 Gbps |

**Critical:** Consul servers require low-latency storage. Use SSD or NVMe, never spinning disks.

---

## Performance Tuning

### Consul Server Configuration

For Druid integration at scale, tune these Consul server parameters:

```hcl
# /etc/consul.d/server.hcl

# Performance tuning
performance {
  raft_multiplier = 1  # Default: 5. Lower = faster convergence, higher load
  leave_drain_time = "5s"
  rpc_hold_timeout = "7s"
}

# Connection limits
limits {
  http_max_conns_per_client = 200      # Per-client HTTP connection limit
  https_max_conns_per_client = 200
  rpc_max_conns_per_client = 100       # Per-client RPC connection limit
  rpc_rate = unlimited                 # Rate limit for RPC (requests/sec)
  kv_max_value_size = 524288          # 512KB - sufficient for Druid metadata
}

# Increase session TTL limits to support Druid's health check intervals
session_ttl_min = "10s"

# Autopilot for automated server management
autopilot {
  cleanup_dead_servers = true
  last_contact_threshold = "200ms"
  max_trailing_logs = 250
  server_stabilization_time = "10s"
}

# Telemetry
telemetry {
  prometheus_retention_time = "60s"
  disable_hostname = false
  metrics_prefix = "consul"
}
```

### Critical Parameters for Druid Integration

#### 1. Session TTL Configuration

The Druid extension uses sessions for leader election. Consul's default `session_ttl_min` is 10s, which aligns with Druid's default health check interval.

**If you change Druid's `healthCheckInterval`, adjust Consul accordingly:**

```hcl
# If Druid uses healthCheckInterval = PT5S
session_ttl_min = "5s"
```

**Warning:** Setting session TTL too low increases Consul server load. Never go below 5s.

#### 2. Blocking Query Tuning

Druid uses blocking queries extensively. Tune these parameters:

```hcl
# Consul server
performance {
  rpc_hold_timeout = "70s"  # Must be > Druid's watchSeconds (default 60s)
}

limits {
  http_max_conns_per_client = 200  # Each Druid node opens ~5-10 long-lived connections
}
```

**Calculation for http_max_conns_per_client:**
```
max_connections = (number_of_node_roles_watched × safety_factor)
                = (5 typical roles × 2) = 10 per Druid node

Add buffer for session operations and health checks: +5
Recommended minimum: 15 per Druid node
```

#### 3. KV Store Performance

Leader election uses the KV store. Enable performance mode:

```hcl
# Consul server
performance {
  raft_multiplier = 1  # Faster Raft consensus (default: 5)
}

# This makes leader election faster but increases server CPU usage
# Only use in production with adequate server resources
```

### Consul Client Agent Configuration

When using client agents (recommended for 20+ nodes):

```hcl
# /etc/consul.d/client.hcl

# Client performance
performance {
  raft_multiplier = 1
  leave_drain_time = "5s"
}

# Cache tuning
cache {
  entry_fetch_max_burst = 10     # Concurrent fetches from servers
  entry_fetch_rate = 10          # Fetches per second
}

# Connection pooling
limits {
  http_max_conns_per_client = 200
  rpc_max_conns_per_client = 100
}

# Service sync
enable_central_service_config = false  # Disable if not using service mesh
enable_agent_tls_for_checks = false    # Enable if using TLS
```

### Druid Configuration Tuning

Match Druid's timing parameters to your SLA requirements:

**Fast Leader Election (< 30s failover):**
```properties
druid.discovery.consul.healthCheckInterval=PT5S
druid.discovery.consul.watchSeconds=PT30S
druid.discovery.consul.watchRetryDelay=PT5S
```

**Balanced (30-60s failover, default):**
```properties
druid.discovery.consul.healthCheckInterval=PT10S
druid.discovery.consul.watchSeconds=PT60S
druid.discovery.consul.watchRetryDelay=PT10S
```

**Reduced Load (60-120s failover):**
```properties
druid.discovery.consul.healthCheckInterval=PT20S
druid.discovery.consul.watchSeconds=PT60S
druid.discovery.consul.watchRetryDelay=PT15S
```

**Trade-offs:**
- Shorter intervals = Faster failure detection + Higher Consul load
- Longer intervals = Slower failure detection + Lower Consul load

---

## ACL Configuration

### Enable ACLs on Consul

ACLs are **strongly recommended** for production deployments.

#### 1. Bootstrap ACL System

```bash
# On Consul server
consul acl bootstrap

# Output will include:
# AccessorID:       a1b2c3d4-...
# SecretID:         e5f6g7h8-...  <- This is your management token
```

**Store the bootstrap token securely** (e.g., Vault, AWS Secrets Manager).

#### 2. Create Druid Policy

```hcl
# druid-policy.hcl

# Service registration and health checks
service "druid-prod-broker" {
  policy = "write"
}
service "druid-prod-coordinator" {
  policy = "write"
}
service "druid-prod-historical" {
  policy = "write"
}
service "druid-prod-middlemanager" {
  policy = "write"
}
service "druid-prod-overlord" {
  policy = "write"
}
service "druid-prod-router" {
  policy = "write"
}

# Service discovery (read all services)
service_prefix "" {
  policy = "read"
}

# Leader election (KV store access)
key_prefix "druid/leader/" {
  policy = "write"
}

# Session management
session_prefix "" {
  policy = "write"
}

# Agent API access (for health checks)
agent_prefix "" {
  policy = "read"
}

# Node information
node_prefix "" {
  policy = "read"
}
```

#### 3. Apply Policy and Create Token

```bash
# Create policy
consul acl policy create \
  -name druid-policy \
  -rules @druid-policy.hcl

# Create token for Druid
consul acl token create \
  -description "Druid cluster token" \
  -policy-name druid-policy

# Output:
# AccessorID:       x1y2z3a4-...
# SecretID:         b5c6d7e8-...  <- Use this in Druid configuration
```

#### 4. Configure Druid with Token

```properties
druid.discovery.consul.aclToken=b5c6d7e8-...
```

**Security Best Practices:**
- Use different tokens per Druid cluster in shared Consul
- Rotate tokens periodically
- Never commit tokens to version control
- Use secret management systems (Vault, AWS Secrets Manager, etc.)

### Multi-Cluster Isolation

If running multiple Druid clusters in the same Consul:

```hcl
# druid-production-policy.hcl
service_prefix "druid-prod-" {
  policy = "write"
}
key_prefix "druid-prod/leader/" {
  policy = "write"
}

# druid-staging-policy.hcl
service_prefix "druid-staging-" {
  policy = "write"
}
key_prefix "druid-staging/leader/" {
  policy = "write"
}
```

Configure each cluster with unique prefixes:
```properties
# Production
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.coordinatorLeaderLockPath=druid-prod/leader/coordinator
druid.discovery.consul.overlordLeaderLockPath=druid-prod/leader/overlord

# Staging
druid.discovery.consul.servicePrefix=druid-staging
druid.discovery.consul.coordinatorLeaderLockPath=druid-staging/leader/coordinator
druid.discovery.consul.overlordLeaderLockPath=druid-staging/leader/overlord
```

---

## Monitoring and Alerting

### Key Metrics to Monitor

#### Consul Server Metrics

**1. Raft Performance**
```
consul.raft.commitTime         # P50, P95, P99 - should be < 10ms
consul.raft.leader.lastContact # Time since last contact with followers
consul.raft.leader.dispatchLog # Log replication time
consul.raft.state.leader       # 1 if leader, 0 otherwise (alert if 0 for >30s)
```

**2. RPC Performance**
```
consul.rpc.request            # Request rate
consul.rpc.request_error      # Error rate (alert if >1% of requests)
consul.client.rpc             # Client RPC latency
consul.client.rpc.failed      # Failed RPC calls (alert if >0)
```

**3. Catalog Operations**
```
consul.catalog.service.query                # Service query rate
consul.catalog.service.query-tag            # Tagged service queries
consul.catalog.service.not-found            # Service not found (alert if high)
```

**4. Session Operations**
```
consul.session_ttl.active          # Number of active sessions (should be ~2 per Druid cluster)
consul.session_ttl.invalidate      # Session invalidation rate
```

**5. KV Store**
```
consul.kvs.apply                   # KV write rate (from leader elections)
consul.kvs.apply_time              # KV write latency (P50, P95, P99)
```

**6. System Resources**
```
consul.runtime.alloc_bytes         # Memory usage
consul.runtime.num_goroutines      # Goroutine count
consul.memberlist.msg.suspect      # Member suspicion events (alert if >0)
```

#### Druid-Specific Consul Metrics

**Health Check Status:**
```bash
# Query health check pass rate
curl -s http://consul:8500/v1/health/state/passing | \
  jq '[.[] | select(.ServiceName | startswith("druid-"))] | length'

# Should equal total number of Druid nodes
```

**Service Discovery Performance:**
```bash
# Query response time for service lookups
time curl -s "http://consul:8500/v1/health/service/druid-prod-broker?passing=true"

# Should be < 100ms
```

**Leader Election Status:**
```bash
# Check current leaders
curl -s http://consul:8500/v1/kv/druid/leader/coordinator?raw
curl -s http://consul:8500/v1/kv/druid/leader/overlord?raw

# Should return URLs of current leaders
```

**Session Health:**
```bash
# List active Druid sessions
curl -s http://consul:8500/v1/session/list | \
  jq '[.[] | select(.Name | contains("druid"))]'

# Should see 2 sessions minimum (coordinator + overlord leaders)
```

### Prometheus Integration

Consul exposes Prometheus metrics on `/v1/agent/metrics?format=prometheus`:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'consul-servers'
    static_configs:
      - targets:
        - consul-01:8500
        - consul-02:8500
        - consul-03:8500
    metrics_path: '/v1/agent/metrics'
    params:
      format: ['prometheus']
```

### Recommended Alerts

```yaml
# Alert: Consul has no leader
- alert: ConsulNoLeader
  expr: consul_raft_state_leader == 0
  for: 30s
  severity: critical
  annotations:
    summary: "Consul cluster has no leader"

# Alert: High Raft commit latency
- alert: ConsulHighRaftLatency
  expr: consul_raft_commitTime{quantile="0.99"} > 50
  for: 5m
  severity: warning
  annotations:
    summary: "Consul Raft commit latency is high (p99 > 50ms)"

# Alert: RPC failures
- alert: ConsulRPCFailures
  expr: rate(consul_client_rpc_failed[5m]) > 0.01
  for: 2m
  severity: warning
  annotations:
    summary: "Consul RPC failures detected"

# Alert: Missing Druid leader
- alert: DruidCoordinatorNoLeader
  expr: absent(consul_health_service_query{service="druid-prod-coordinator"})
  for: 1m
  severity: critical
  annotations:
    summary: "Druid Coordinator leader not registered in Consul"

# Alert: Druid nodes down
- alert: DruidNodeDown
  expr: consul_health_service_query{service=~"druid-.*",status="passing"} < expected_count
  for: 2m
  severity: warning
  annotations:
    summary: "Druid node count below expected"

# Alert: Session invalidation spike
- alert: ConsulSessionInvalidationSpike
  expr: rate(consul_session_ttl_invalidate[5m]) > 0.1
  for: 5m
  severity: warning
  annotations:
    summary: "High rate of Consul session invalidations (possible network issues)"
```

### Logging

**Enable debug logging temporarily for troubleshooting:**

```bash
# On Consul server
consul monitor -log-level=debug

# Or via API
curl -X PUT http://consul:8500/v1/agent/log-level -d '"debug"'
```

**Druid-side logging:**
```xml
<!-- log4j2.xml -->
<Logger name="org.apache.druid.consul.discovery" level="debug"/>
```

**What to look for:**
- `Created Consul session` - Session creation events
- `Became leader` - Leader election events
- `Lost leadership` - Leader step-down events
- `Successfully announced` - Service registration
- `Watching for changes` - Blocking query activity

---

## Capacity Planning

### Estimating Consul Load

**Service Catalog Load:**
```
Registrations = Number of Druid nodes
Health checks/sec = Number of Druid nodes / healthCheckInterval (seconds)
                  = 100 nodes / 10s = 10 checks/sec
```

**Blocking Query Load:**
```
Concurrent queries = Druid nodes × Roles watched
                   = 100 nodes × 4 roles = 400 concurrent connections

Query refresh rate = 1 / watchSeconds
                   = 1 / 60s = 0.0167 queries/sec per connection

Total query rate = 400 × 0.0167 = 6.7 queries/sec
```

**Leader Election Load:**
```
Active sessions = 2 (Coordinator + Overlord leaders)
Session renewals/sec = 2 / healthCheckInterval
                     = 2 / 10s = 0.2 renewals/sec

Lock operations during election = (Number of Coordinator candidates +
                                   Number of Overlord candidates) ×
                                   (1 / healthCheckInterval)
```

### Sizing Calculator

**Small Deployment (< 20 Druid nodes):**
- Consul Servers: 3
- Server Resources: 2 CPU, 4 GB RAM, 20 GB SSD each
- Expected Load: ~50 requests/sec, ~100 concurrent connections
- Deployment Pattern: Direct connection from Druid to Consul servers

**Medium Deployment (20-100 Druid nodes):**
- Consul Servers: 5
- Server Resources: 4 CPU, 8 GB RAM, 50 GB SSD each
- Expected Load: ~200 requests/sec, ~500 concurrent connections
- Deployment Pattern: Consul client agents on each Druid host

**Large Deployment (100-500 Druid nodes):**
- Consul Servers: 5-7
- Server Resources: 8 CPU, 16 GB RAM, 100 GB NVMe each
- Expected Load: ~1000 requests/sec, ~2000 concurrent connections
- Deployment Pattern: Client agents + connection pooling

**Very Large Deployment (500+ Druid nodes):**
- Consul Servers: 7
- Server Resources: 16 CPU, 32 GB RAM, 200 GB NVMe each
- Expected Load: ~3000+ requests/sec, ~5000+ concurrent connections
- Deployment Pattern: Multi-region federation with client agents
- Additional: Consider read replicas (Consul Enterprise)

### Growth Planning

Plan for 2x growth capacity:

```
Current cluster: 100 Druid nodes
Plan for: 200 Druid nodes
Consul server sizing: Target 200-node capacity
```

**Monitoring for capacity issues:**
- Raft commit time increasing (>50ms p99)
- RPC latency increasing (>100ms p99)
- Leader election taking >5s
- High CPU usage on Consul servers (>70%)

---

## Network Requirements

### Bandwidth

**Per Druid Node:**
- Health check updates: ~1 KB every 10s = 0.1 KB/s
- Blocking query responses: ~5-10 KB per query × 4 queries/min = 0.3 KB/s
- Service registration: ~10 KB (one-time at startup)
- Total sustained: ~0.5 KB/s per node

**For 100 nodes:**
- Total bandwidth to Consul: ~50 KB/s (~400 Kbps)
- Peak during cluster restart: ~1 MB/s for 30 seconds

**Recommendation:** 100 Mbps network is sufficient for up to 1000 Druid nodes.

### Latency Requirements

**Critical:**
- Druid to Consul latency should be <10ms for optimal performance
- Cross-datacenter latency <50ms for WAN federation
- >100ms latency will cause slow leader elections and delayed failure detection

**Testing latency:**
```bash
# From Druid node to Consul
time curl -s http://consul:8500/v1/status/leader

# Should be < 10ms
```

### Port Requirements

| Port | Protocol | Purpose | Source | Destination |
|------|----------|---------|--------|-------------|
| 8500 | TCP | HTTP API | Druid nodes | Consul servers/agents |
| 8501 | TCP | HTTPS API | Druid nodes | Consul servers/agents (if TLS) |
| 8300 | TCP | Server RPC | Consul agents | Consul servers |
| 8301 | TCP/UDP | LAN Gossip | All Consul nodes | All Consul nodes |
| 8302 | TCP/UDP | WAN Gossip | Consul servers | Consul servers (multi-DC) |
| 8600 | TCP/UDP | DNS | Optional | Consul servers/agents |

**Firewall Rules:**

```bash
# Allow Druid nodes to reach Consul HTTP API
iptables -A INPUT -p tcp --dport 8500 -s <druid_network> -j ACCEPT

# Allow Consul agents to reach servers
iptables -A INPUT -p tcp --dport 8300 -s <consul_agent_network> -j ACCEPT

# Allow Consul gossip between all Consul nodes
iptables -A INPUT -p tcp --dport 8301 -s <consul_network> -j ACCEPT
iptables -A INPUT -p udp --dport 8301 -s <consul_network> -j ACCEPT
```

### DNS Considerations

**Option 1: DNS Round-Robin** (Recommended for small deployments)
```
consul.example.com -> 10.0.1.10, 10.0.1.11, 10.0.1.12
```

```properties
druid.discovery.consul.host=consul.example.com
```

**Option 2: Load Balancer** (Recommended for medium+ deployments)
```
consul-lb.example.com -> Load Balancer -> Consul servers
```

**Option 3: Localhost Agent** (Recommended for large deployments)
```properties
druid.discovery.consul.host=localhost
druid.discovery.consul.port=8500
```
Requires Consul client agent on each Druid host.

---

## Disaster Recovery

### Backup Strategy

#### 1. Snapshot Consul State

```bash
# Create snapshot (includes KV store, catalog, sessions)
consul snapshot save backup-$(date +%Y%m%d-%H%M%S).snap

# Automate with cron
0 */6 * * * consul snapshot save /backups/consul-$(date +\%Y\%m\%d-\%H\%M\%S).snap
```

**What's included:**
- KV store (leader election locks)
- Service catalog registrations
- ACL policies and tokens
- Prepared queries

**Not included:**
- Active sessions (ephemeral by design)
- Health check states (rebuilt on restore)

#### 2. Store Snapshots Safely

```bash
# Copy to S3
aws s3 cp backup.snap s3://company-consul-backups/druid-cluster/

# Or to Azure Blob
az storage blob upload --container consul-backups --file backup.snap
```

**Retention policy:**
- Keep last 7 daily snapshots
- Keep last 4 weekly snapshots
- Keep last 12 monthly snapshots

#### 3. Restore from Snapshot

```bash
# Restore snapshot
consul snapshot restore backup-20250107-120000.snap

# Verify cluster health
consul members
consul operator raft list-peers
```

**After restore:**
- Service registrations will be stale (Druid nodes re-register automatically)
- Sessions are recreated on next health check
- Leader elections re-occur within 30 seconds
- No Druid restart needed

### Failure Scenarios

#### Scenario 1: Single Consul Server Failure

**Impact:** None (with 3+ servers)

**Resolution:**
1. Consul cluster continues operating
2. Replace failed server
3. Join new server to cluster

```bash
# On new server
consul agent -server -join consul-01
```

#### Scenario 2: Consul Cluster Quorum Loss (2 of 3 servers down)

**Impact:**
- Service discovery stops working
- Leader elections cannot complete
- Druid nodes cannot discover each other
- **Druid cluster becomes non-functional**

**Resolution:**
1. Restore quorum ASAP (bring up 2 servers)
2. If data is lost, restore from snapshot
3. Druid nodes will automatically reconnect and re-register

**Prevention:**
- Run 5 servers (can tolerate 2 failures)
- Monitor server health continuously
- Have automated server replacement

#### Scenario 3: Network Partition

**Impact:**
- Druid nodes on minority side lose Consul access
- Leader elections may fail
- Service discovery returns stale data

**Resolution:**
1. Fix network partition
2. Consul automatically heals via gossip
3. Druid re-discovers nodes within 60 seconds

**Prevention:**
- Ensure low-latency network between Consul servers
- Use anti-affinity for Consul server placement
- Monitor network latency and packet loss

#### Scenario 4: Consul Completely Down

**Impact:**
- Druid cannot discover new nodes
- Leader elections fail
- Existing connections continue working (Druid doesn't kill existing connections)

**Resolution:**
1. Restore Consul cluster from snapshot
2. Wait for Druid nodes to re-register (~30 seconds)
3. Leader elections complete automatically

**Mitigation:**
- Existing Druid clusters can continue processing queries during Consul outage
- New nodes cannot join
- Failed leaders cannot be replaced

#### Scenario 5: Stale Leader Locks

If leader election locks become stale (e.g., leader crashed without cleaning up):

```bash
# Check current lock holder
consul kv get druid/leader/coordinator

# If stale, delete manually (Consul will auto-release on next session TTL expiry)
# Only do this if you're certain the leader is dead
consul kv delete druid/leader/coordinator

# Leader election will complete within 10 seconds
```

### Multi-Datacenter Disaster Recovery

For multi-DC deployments with WAN federation:

**Setup:**
```hcl
# DC1 (primary)
datacenter = "dc1"
primary_datacenter = "dc1"

# DC2 (DR)
datacenter = "dc2"
primary_datacenter = "dc1"

# WAN join
retry_join_wan = ["consul-dc1-01", "consul-dc1-02"]
```

**Failover process:**
1. Promote DC2 to primary
2. Update Druid configuration to point to DC2 Consul
3. Restart Druid nodes (required to change Consul endpoint)

**Better approach: Use DNS for automatic failover**
```properties
druid.discovery.consul.host=consul.global.example.com
# DNS switches from DC1 to DC2 during failover
```

---

## Troubleshooting

### Common Issues

#### 1. Druid Node Not Appearing in Service Catalog

**Symptoms:**
- Other Druid nodes can't discover this node
- `consul catalog services` doesn't show the service

**Diagnosis:**
```bash
# Check Druid logs
grep "Successfully announced" /var/log/druid/*.log

# Check Consul agent
consul catalog services | grep druid

# Check specific service
consul catalog nodes -service=druid-prod-broker
```

**Common causes:**
- ACL token missing or invalid
- Network connectivity to Consul
- Service registration failed (check Druid logs)

**Resolution:**
```bash
# Test Consul connectivity
curl http://consul:8500/v1/status/leader

# Test ACL token
curl -H "X-Consul-Token: <token>" http://consul:8500/v1/catalog/services

# Restart Druid node to re-register
systemctl restart druid-broker
```

#### 2. Leader Election Not Completing

**Symptoms:**
- Coordinator shows "Waiting for leadership"
- No leader in `consul kv get druid/leader/coordinator`

**Diagnosis:**
```bash
# Check session exists
consul session list | grep druid

# Check lock status
consul kv get -detailed druid/leader/coordinator

# Check Druid logs
grep -i "leader" /var/log/druid/coordinator.log
```

**Common causes:**
- Session creation failing (ACL permissions)
- Lock contention (multiple leaders competing)
- Network issues between Druid and Consul

**Resolution:**
```bash
# Verify ACL permissions
consul acl policy read -name druid-policy

# Check session TTL configuration
consul operator raft list-peers

# Delete stale lock (last resort)
consul kv delete druid/leader/coordinator
```

#### 3. High Consul CPU Usage

**Symptoms:**
- Consul servers at >80% CPU
- Slow leader elections (>30s)
- High Raft commit latency

**Diagnosis:**
```bash
# Check active connections
netstat -an | grep :8300 | wc -l

# Check blocking queries
consul debug -duration=30s -interval=10s

# Check metrics
curl http://consul:8500/v1/agent/metrics?format=prometheus | \
  grep consul_raft_commitTime
```

**Common causes:**
- Too many blocking queries
- `watchSeconds` too low
- `healthCheckInterval` too aggressive
- Insufficient Consul server resources

**Resolution:**
1. Increase `watchSeconds` to 120s
2. Increase `healthCheckInterval` to 20s
3. Deploy Consul client agents to offload blocking queries
4. Scale up Consul server hardware

#### 4. Service Discovery Lag

**Symptoms:**
- New Druid nodes take >60s to be discovered
- Removed nodes still appear in discovery

**Diagnosis:**
```bash
# Check watch performance
time curl "http://consul:8500/v1/health/service/druid-prod-broker?passing=true&wait=60s&index=0"

# Should return in ~60s or immediately on change
```

**Common causes:**
- Long `watchSeconds` configuration
- Network latency to Consul
- Consul servers overloaded

**Resolution:**
- Reduce `watchSeconds` to 30s for faster discovery
- Ensure <10ms latency to Consul
- Scale Consul cluster

#### 5. Session Expiration Storms

**Symptoms:**
- Frequent leader re-elections
- Logs show "Session expired" repeatedly

**Diagnosis:**
```bash
# Check session invalidation rate
consul monitor | grep "session invalidated"

# Check network latency
ping -c 100 consul-server
```

**Common causes:**
- Network instability
- Consul servers overloaded
- Session TTL too short

**Resolution:**
1. Increase `healthCheckInterval` to 15s or 20s
2. Fix network issues
3. Scale Consul servers

#### 6. Memory Leak in Consul

**Symptoms:**
- Consul memory usage growing unbounded
- OOM kills

**Diagnosis:**
```bash
# Check memory usage
consul operator raft list-peers

# Check for leaked sessions
consul session list | wc -l  # Should be ~2 per Druid cluster
```

**Common causes:**
- Session leaks (Druid nodes crashing without cleanup)
- Large number of services
- Consul bug

**Resolution:**
```bash
# Clean up orphaned sessions
consul session list | grep -v "<active_leader_id>" | \
  while read id; do consul session destroy $id; done

# Restart Consul server (rolling restart)
```

### Debug Checklist

When troubleshooting Druid + Consul issues:

- [ ] Verify Consul cluster is healthy (`consul members`)
- [ ] Check Consul leader exists (`consul operator raft list-peers`)
- [ ] Test network connectivity from Druid to Consul
- [ ] Verify ACL token is valid and has correct permissions
- [ ] Check Druid logs for Consul-related errors
- [ ] Verify service registrations exist (`consul catalog services`)
- [ ] Check leader lock status (`consul kv get druid/leader/coordinator`)
- [ ] Verify sessions are active (`consul session list`)
- [ ] Check Consul server load (CPU, memory, connections)
- [ ] Review Consul server logs (`journalctl -u consul`)

---

## Production Checklist

Before deploying to production:

### Pre-Deployment

- [ ] Consul cluster sized appropriately (see capacity planning)
- [ ] ACLs enabled and Druid policy created
- [ ] TLS enabled for Consul (if required by security policy)
- [ ] Monitoring configured (Prometheus + alerts)
- [ ] Backup automation configured (snapshot every 6 hours)
- [ ] Network latency verified (<10ms Druid to Consul)
- [ ] Firewall rules configured
- [ ] DNS or load balancer configured for Consul access

### Consul Server Configuration

- [ ] `raft_multiplier` tuned (1 for performance, 5 for stability)
- [ ] `session_ttl_min` matches Druid's `healthCheckInterval`
- [ ] `rpc_hold_timeout` > Druid's `watchSeconds`
- [ ] `http_max_conns_per_client` sized for Druid node count
- [ ] Autopilot enabled for server management
- [ ] Telemetry enabled for monitoring

### Druid Configuration

- [ ] `servicePrefix` unique per cluster (if sharing Consul)
- [ ] `aclToken` configured
- [ ] `healthCheckInterval` appropriate for SLA (10-20s typical)
- [ ] `watchSeconds` balanced for discovery speed vs load (30-60s)
- [ ] `coordinatorLeaderLockPath` and `overlordLeaderLockPath` unique per cluster
- [ ] TLS configured (if Consul uses HTTPS)

### Testing

- [ ] Service discovery tested (new node appears within `watchSeconds`)
- [ ] Leader election tested (Coordinator and Overlord elect leaders)
- [ ] Failover tested (kill leader, new leader elected within 30s)
- [ ] Network partition tested (Consul heals automatically)
- [ ] Consul restart tested (Druid recovers automatically)
- [ ] Load tested (verify Consul handles expected request rate)

### Documentation

- [ ] Runbook created for operations team
- [ ] Disaster recovery procedures documented
- [ ] Escalation paths defined
- [ ] Backup and restore procedures tested

---

## Additional Resources

- [Consul Documentation](https://www.consul.io/docs)
- [Consul Performance Tuning Guide](https://www.consul.io/docs/install/performance)
- [Consul Production Deployment Guide](https://learn.hashicorp.com/tutorials/consul/deployment-guide)
- [Consul ACL System Guide](https://www.consul.io/docs/security/acl)
- [Druid Documentation](https://druid.apache.org/docs/latest/)

---

## Appendix: Sample Configurations

### Complete Consul Server Configuration

```hcl
# /etc/consul.d/server.hcl

# Server basics
datacenter = "dc1"
data_dir = "/opt/consul/data"
server = true
bootstrap_expect = 3

# Networking
bind_addr = "{{ GetPrivateIP }}"
client_addr = "0.0.0.0"
advertise_addr = "{{ GetPrivateIP }}"

# Cluster joining
retry_join = ["consul-01", "consul-02", "consul-03"]

# ACLs
acl {
  enabled = true
  default_policy = "deny"
  enable_token_persistence = true
  tokens {
    agent = "agent-token-here"
  }
}

# Performance
performance {
  raft_multiplier = 1
  leave_drain_time = "5s"
  rpc_hold_timeout = "70s"
}

limits {
  http_max_conns_per_client = 200
  rpc_max_conns_per_client = 100
  kv_max_value_size = 524288
}

# Session configuration
session_ttl_min = "10s"

# Autopilot
autopilot {
  cleanup_dead_servers = true
  last_contact_threshold = "200ms"
  max_trailing_logs = 250
  server_stabilization_time = "10s"
}

# Telemetry
telemetry {
  prometheus_retention_time = "60s"
  disable_hostname = false
}

# TLS (optional but recommended)
tls {
  defaults {
    ca_file = "/etc/consul.d/certs/ca.pem"
    cert_file = "/etc/consul.d/certs/server.pem"
    key_file = "/etc/consul.d/certs/server-key.pem"
    verify_incoming = true
    verify_outgoing = true
  }
}
```

### Complete Druid Configuration

```properties
# extensions-core/druid-core/src/main/resources/druid.properties

# Load Consul extension
druid.extensions.loadList=["druid-consul-extensions", "druid-hdfs-storage", "druid-kafka-indexing-service"]

# Discovery using Consul
druid.discovery.type=consul

# Consul connection
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.datacenter=dc1

# ACL
druid.discovery.consul.aclToken=<your-token-here>

# TLS (if Consul uses HTTPS)
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/etc/druid/certs/consul-ca.pem

# Timing (balanced configuration)
druid.discovery.consul.healthCheckInterval=PT10S
druid.discovery.consul.deregisterAfter=PT90S
druid.discovery.consul.watchSeconds=PT60S
druid.discovery.consul.watchRetryDelay=PT10S
druid.discovery.consul.maxWatchRetries=9223372036854775807

# Leader election
druid.coordinator.selector.type=consul
druid.indexer.selector.type=consul
druid.discovery.consul.coordinatorLeaderLockPath=druid-prod/leader/coordinator
druid.discovery.consul.overlordLeaderLockPath=druid-prod/leader/overlord

# HTTP-based segment management (required)
druid.serverview.type=http
druid.indexer.runner.type=httpRemote
```

---

**Document Version:** 1.0
**Last Updated:** 2025-01-07
**Maintainer:** Platform Engineering Team
