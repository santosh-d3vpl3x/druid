---
id: consul
title: "Consul-based Service Discovery"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

Apache Druid extension to enable using HashiCorp Consul for node discovery. This extension allows Druid clusters to use Consul's service catalog for discovering nodes, as an alternative to ZooKeeper or Kubernetes-based discovery.

## Quick Start

To use this Apache Druid extension, [include](../../configuration/extensions.md#loading-extensions) `druid-consul-extensions` in the extensions load list.

```properties
# Load the extension
druid.extensions.loadList=["druid-consul-extensions"]

# Minimal Consul configuration
druid.discovery.type=consul
druid.discovery.consul.host=localhost
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid

# Required HTTP-based configurations
druid.serverview.type=http
druid.indexer.runner.type=httpRemote

# Enable Consul-based leader election
druid.coordinator.selector.type=consul
druid.indexer.selector.type=consul
```

For production deployments, see the [Configuration](#configuration) section below for security, TLS, and advanced options.

## Configuration

This extension works together with HTTP-based segment and task management in Druid. The following configurations must be set on all Druid nodes:

```
druid.serverview.type=http
druid.indexer.runner.type=httpRemote
druid.discovery.type=consul
druid.coordinator.selector.type=consul
druid.indexer.selector.type=consul
```

**Note:** This extension provides complete ZooKeeper replacement, including both service discovery and leader election for Coordinator and Overlord roles.

### Properties

| Property | Possible Values | Description | Default | Required |
|----------|-----------------|-------------|---------|--------|
| `druid.discovery.consul.host` | String | Consul agent hostname or IP address. | `localhost` | No |
| `druid.discovery.consul.port` | Integer | Consul agent HTTP API port. | `8500` | No |
| `druid.discovery.consul.servicePrefix` | String | Prefix for Consul service names; namespaces clusters. | None | Yes |
| `druid.discovery.consul.aclToken` | String | Consul ACL token for authentication. | None | No |
| `druid.discovery.consul.datacenter` | String | Consul datacenter for registration and discovery. | Default datacenter | No |
| `druid.discovery.consul.basicAuthUser` | String | Username for HTTP basic authentication (preemptive Basic Auth). | None | No |
| `druid.discovery.consul.basicAuthPassword` | String | Password for HTTP basic authentication. | None | No |
| `druid.discovery.consul.healthCheckInterval` | ISO8601 Duration | Update interval for Consul health checks. | `PT10S` | No |
| `druid.discovery.consul.deregisterAfter` | ISO8601 Duration | Deregister service after health check fails. | `PT90S` | No |
| `druid.discovery.consul.watchSeconds` | ISO8601 Duration | Blocking query timeout for service changes. | `PT60S` | No |
| `druid.discovery.consul.maxWatchRetries` | Long | Max watch retries before giving up. Use `-1` for unlimited (default). | `unlimited` | No |
| `druid.discovery.consul.watchRetryDelay` | ISO8601 Duration | Wait time before retrying failed watch. | `PT10S` | No |
| `druid.discovery.consul.coordinatorLeaderLockPath` | String | Consul KV path for Coordinator leader lock. | `druid/leader/coordinator` | No |
| `druid.discovery.consul.overlordLeaderLockPath` | String | Consul KV path for Overlord leader lock. | `druid/leader/overlord` | No |
| `druid.discovery.consul.serviceTags.*` | String | Additional Consul service tags as key/value pairs (rendered as `key:value`). Useful for AZ/tier/version. | None | No |

### TLS Configuration

This extension uses Druid's standard TLS configuration for secure HTTPS connections to Consul. To enable TLS, configure the SSL client properties under `druid.discovery.consul.sslClientConfig.*`.

Notes:
- If `sslClientConfig` is provided but TLS initialization fails (e.g., invalid truststore), the Consul client fails fast and does not fall back to plain HTTP.
- If HTTP Basic Auth is configured without TLS, credentials are sent over plain HTTP; this is strongly discouraged and will log a warning.

TLS properties:

| Property | Description | Default |
|----------|-------------|---------|
| `druid.discovery.consul.sslClientConfig.protocol` | SSL/TLS protocol to use. | `TLSv1.2` |
| `druid.discovery.consul.sslClientConfig.trustStoreType` | Type of truststore (PKCS12, JKS, etc.). | `java.security.KeyStore.getDefaultType()` |
| `druid.discovery.consul.sslClientConfig.trustStorePath` | Path to truststore for verifying Consul server certificates. [Required for TLS] | None |
| `druid.discovery.consul.sslClientConfig.trustStoreAlgorithm` | Algorithm for TrustManagerFactory. | `javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()` |
| `druid.discovery.consul.sslClientConfig.trustStorePassword` | Password for truststore. Can use [password providers](../../operations/password-provider.md). | None |
| `druid.discovery.consul.sslClientConfig.keyStoreType` | Type of keystore for client certificate (PKCS12, JKS, etc.). | `java.security.KeyStore.getDefaultType()` |
| `druid.discovery.consul.sslClientConfig.keyStorePath` | Path to keystore with client certificate for mTLS. [Optional] | None |
| `druid.discovery.consul.sslClientConfig.keyStorePassword` | Password for keystore. Can use [password providers](../../operations/password-provider.md). | None |
| `druid.discovery.consul.sslClientConfig.keyManagerPassword` | Password for key manager within keystore. Can use [password providers](../../operations/password-provider.md). | None |
| `druid.discovery.consul.sslClientConfig.keyManagerFactoryAlgorithm` | Algorithm for KeyManagerFactory. | `javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()` |
| `druid.discovery.consul.sslClientConfig.certAlias` | Alias of certificate to use from keystore for mTLS. [Optional] | None |
| `druid.discovery.consul.sslClientConfig.validateHostnames` | Verify Consul server hostname in certificate. | `true` |

For details on supported keystore formats and additional configuration options, see the [Simple Client SSL Context extension](../../development/extensions-core/simple-client-sslcontext.md) documentation.

### Example Configuration

For a typical deployment:

```properties
# Extension loading
druid.extensions.loadList=["druid-consul-extensions", ...]

# Discovery configuration
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid-prod

# HTTP-based segment and task management
druid.serverview.type=http
druid.indexer.runner.type=httpRemote

# Leader election using Consul (replaces ZooKeeper)
druid.coordinator.selector.type=consul
druid.indexer.selector.type=consul

# Optional: discovery watch retry behavior
# Use -1 for unlimited retries (default). Set a positive number to stop after N consecutive failures.
# druid.discovery.consul.maxWatchRetries=-1
```

For a secure Consul cluster with ACL:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.aclToken=your-secret-acl-token
druid.discovery.consul.datacenter=dc1
```

For TLS-enabled Consul with server certificate verification:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8501
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.aclToken=your-secret-acl-token

# TLS configuration (standard Druid properties)
druid.discovery.consul.sslClientConfig.trustStorePath=/etc/druid/certs/consul-ca-truststore.jks
druid.discovery.consul.sslClientConfig.trustStorePassword=truststore-password
druid.discovery.consul.sslClientConfig.validateHostnames=true
```

For Consul with mutual TLS (mTLS) authentication:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8501
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.aclToken=your-secret-acl-token

# TLS with client certificates (mutual TLS)
druid.discovery.consul.sslClientConfig.trustStorePath=/etc/druid/certs/consul-ca-truststore.jks
druid.discovery.consul.sslClientConfig.trustStorePassword=truststore-password
druid.discovery.consul.sslClientConfig.keyStorePath=/etc/druid/certs/druid-client-keystore.p12
druid.discovery.consul.sslClientConfig.keyStorePassword=keystore-password
druid.discovery.consul.sslClientConfig.validateHostnames=true
```

### Datacenter (DC) Scope and Regions

Consul "datacenter" (DC) is the scope for the service catalog, KV, sessions and locks. In cloud deployments, a single DC typically maps to a cloud region and spans multiple AZs (Consul servers are deployed across AZs for resilience). Leader election uses KV+sessions and is therefore DC‑scoped: all Coordinator/Overlord candidates must talk to the same DC to ensure a single regional leader.

If you operate one Consul DC per region (recommended), you can omit `druid.discovery.consul.datacenter` and rely on the local agent's DC. If you run multiple DCs, set `druid.discovery.consul.datacenter` to pin discovery and election to the intended DC and avoid split‑brain.

### Health Check TTL Behavior

When registering services, the TTL for the Consul health check is set to 3x `druid.discovery.consul.healthCheckInterval` (with a minimum of 30s). Heartbeats are sent every `healthCheckInterval`. This margin avoids flapping due to scheduling jitter or transient delays. You can tune `healthCheckInterval` to balance responsiveness with traffic to your Consul agents.

## How It Works

### Service Registration

When a Druid node starts, it registers itself with Consul as a service:
- Service name: `{servicePrefix}-{nodeRole}` (e.g., `druid-prod-broker`)
- Service ID: `{servicePrefix}-{nodeRole}-{host}-{port}` (e.g., `druid-prod-broker-host1-8082`)
- Service tags: `druid`, `role:{nodeRole}`
- Service metadata: Full `DiscoveryDruidNode` JSON stored in Consul service metadata
- Health check: TTL-based health check that is automatically updated

### Service Discovery

Druid nodes discover each other by:
1. Querying Consul's health service API for services with a specific role
2. Watching for changes using Consul's blocking queries
3. Notifying listeners when nodes are added or removed

### Health Checks

The extension maintains service health by:
- Registering a TTL-based health check with each service
- Periodically updating the health check status (default: every 10 seconds)
- Consul automatically deregisters services whose health checks fail for too long (default: 90 seconds)

### Leader Election

The extension provides leader election for Coordinator and Overlord using Consul sessions and locks:

1. **Session Creation**: Each leader candidate creates a Consul session with TTL
2. **Lock Acquisition**: Candidates attempt to acquire a lock on a KV key using the session
3. **Leadership**: The first to acquire the lock becomes the leader
4. **Session Renewal**: Leaders periodically renew their session to maintain the lock
5. **Failure Detection**: If a leader fails to renew its session, Consul automatically releases the lock
6. **Re-election**: Other candidates detect the lock release and compete for leadership

This provides the same ephemeral node behavior as ZooKeeper's leader election, completely removing the need for ZooKeeper in your Druid deployment.

#### Leader Election Timing Details

- **Session TTL**: Automatically calculated as `max(15 seconds, healthCheckInterval × 3)`
  - Example: If `healthCheckInterval=PT10S`, session TTL = 30 seconds
  - Minimum session TTL is 15 seconds (Consul requirement)
  - This determines how long before a failed leader's lock is released

- **Session Renewal Frequency**: Equals `healthCheckInterval` (default: 10 seconds)
  - Leaders renew their session at this interval to maintain the lock

- **Lock Delay**: 5 seconds
  - After a session is invalidated, prevents immediate re-acquisition of the lock
  - Reduces lock thrashing during network instability

- **Leader Failover Time**: Typically between 15-45 seconds
  - Depends on session TTL and when the failure is detected
  - In the default configuration (10s health check interval):
    - Session TTL: 30 seconds
    - Expected failover: 10-30 seconds after leader failure

#### Network Partition Behavior

During a network partition:
- If the leader loses connectivity to Consul, its session will expire after the TTL
- Once the session expires, Consul automatically releases the lock
- Other candidates can acquire the lock and become leader
- When network connectivity is restored, the old leader will detect it's no longer the leader and step down

## Authentication Methods

The extension supports multiple authentication methods for securing communication with Consul:

### 1. ACL Token Authentication (Recommended)

Most common for production deployments:

```properties
druid.discovery.consul.aclToken=your-secret-token
```

The token must have appropriate permissions (see Consul ACL Permissions section below).

### 2. TLS/HTTPS with Certificate Verification

For encrypted communication and server verification, configure a truststore containing Consul's CA certificate:

```properties
# TLS configuration using truststore
druid.discovery.consul.sslClientConfig.protocol=TLSv1.3
druid.discovery.consul.sslClientConfig.trustStoreType=PKCS12
druid.discovery.consul.sslClientConfig.trustStorePath=/path/to/truststore.p12
druid.discovery.consul.sslClientConfig.trustStorePassword=truststore-password
druid.discovery.consul.sslClientConfig.validateHostnames=true
```

### 3. Mutual TLS (mTLS) Authentication

For strongest security, use client certificates in addition to server verification:

```properties
# TLS with both truststore and keystore (mTLS)
druid.discovery.consul.sslClientConfig.protocol=TLSv1.3

# Server verification (truststore with Consul CA)
druid.discovery.consul.sslClientConfig.trustStoreType=PKCS12
druid.discovery.consul.sslClientConfig.trustStorePath=/path/to/truststore.p12
druid.discovery.consul.sslClientConfig.trustStorePassword=truststore-password

# Client authentication (keystore with client certificate)
druid.discovery.consul.sslClientConfig.keyStoreType=PKCS12
druid.discovery.consul.sslClientConfig.keyStorePath=/path/to/client-keystore.p12
druid.discovery.consul.sslClientConfig.keyStorePassword=keystore-password
druid.discovery.consul.sslClientConfig.certAlias=client

# Hostname verification
druid.discovery.consul.sslClientConfig.validateHostnames=true
```

**Creating Keystores from PEM Files:**

If you have PEM certificate and key files, convert them to PKCS12 keystores:

```bash
# 1. Create client keystore from PEM certificate and private key
openssl pkcs12 -export \
  -in client-cert.pem \
  -inkey client-key.pem \
  -out client-keystore.p12 \
  -name client \
  -passout pass:keystore-password

# 2. Create truststore from Consul CA certificate
keytool -import \
  -file consul-ca.pem \
  -alias consul-ca \
  -keystore truststore.p12 \
  -storetype PKCS12 \
  -storepass truststore-password \
  -noprompt
```

**Notes:**
- Both JKS and PKCS12 keystore types are supported
- PKCS12 is recommended as the industry standard
- Keystores can contain both encrypted and unencrypted private keys
- The `certAlias` must match the alias used when creating the keystore (e.g., "client" in the example above)
- If hostname verification is not desired (e.g., lab environments), set `validateHostnames=false`

### 4. Basic Authentication

For simple HTTP basic auth (less common):

```properties
druid.discovery.consul.basicAuthUser=username
druid.discovery.consul.basicAuthPassword=password
```

### 5. Combined Authentication

You can combine methods for defense-in-depth. For example, TLS/mTLS + ACL token (recommended):

```properties
# TLS + ACL token
druid.discovery.consul.aclToken=your-token
druid.discovery.consul.sslClientConfig.trustStorePath=/path/to/truststore.p12
druid.discovery.consul.sslClientConfig.trustStorePassword=truststore-password

# Optional: mTLS (client certificate)
druid.discovery.consul.sslClientConfig.keyStorePath=/path/to/client-keystore.p12
druid.discovery.consul.sslClientConfig.keyStorePassword=keystore-password
druid.discovery.consul.sslClientConfig.certAlias=client
```

## Requirements

- Consul 1.0.0 or higher
- Network connectivity from all Druid nodes to Consul agent
- If using Consul ACL, appropriate ACL token with permissions to:
  - Register and deregister services
  - Read service catalog
  - Update health checks
- If using TLS/mTLS:
  - Valid certificates issued by trusted CA
  - Certificate files accessible to Druid processes
  - Consul configured to accept TLS connections

## Consul ACL Permissions

If Consul ACL is enabled, the ACL token must have the following permissions:

### For Service Discovery Only

```hcl
service "{servicePrefix}-" {
  policy = "write"
}

service_prefix "" {
  policy = "read"
}
```

### For Leader Election (Coordinator/Overlord)

In addition to the service permissions above, the Coordinator and Overlord nodes require KV and session permissions for leader election:

```hcl
# KV permissions for leader election locks
key_prefix "druid/leader/" {
  policy = "write"
}

# Session permissions for creating and managing Consul sessions
session_prefix "" {
  policy = "write"
}
```

### Complete ACL Policy Example

For a complete Druid deployment using Consul for both discovery and leader election:

```hcl
# Service discovery permissions (all nodes)
service "druid-prod-" {
  policy = "write"
}

service_prefix "" {
  policy = "read"
}

# Leader election permissions (Coordinator and Overlord only)
key_prefix "druid/leader/" {
  policy = "write"
}

session_prefix "" {
  policy = "write"
}
```

**Note:** You can create separate ACL tokens for different node types:
- Broker, Historical, MiddleManager: Only need service permissions
- Coordinator, Overlord: Need both service and leader election permissions

## Monitoring

### Consul Monitoring

Monitor the following in your Consul cluster:
- Service registrations for each Druid node role
- Health check status for all Druid services
- Consul agent connectivity
- Leader election locks in KV store (keys under `druid/leader/coordinator` and `druid/leader/overlord`)
- Session status for Coordinator and Overlord nodes

### Druid Logs

Check Druid logs for:
- `Successfully announced DiscoveryDruidNode` - Node registered successfully
- `Failed to announce` - Registration errors
- `Exception while watching for role` - Discovery errors
- `Created Consul session [%s] for leader election` - Leader election session created
- `Failed to renew session` - Session renewal failures (may indicate network issues)
- `Became leader` / `Lost leadership` - Leader election state changes

### Metrics and Observability

The extension emits lightweight Druid metrics when a ServiceEmitter is available:
- consul/announce/success|failure — node announce/unannounce outcomes
- consul/healthcheck/failure — TTL heartbeat update failures
- consul/watch/error|added|removed — discovery watch errors and changes
- consul/leader/become|stop|renew/fail — leader election transitions

You should also monitor:
1. Consul's built-in metrics and health checks
2. Druid application logs
3. Consul UI for visualizing service health and registrations

**Recommended Alerts:**
- Alert when Druid services disappear from Consul catalog
- Alert when health checks fail for extended periods
- Alert on frequent leader election changes (indicates instability)
- Alert when Consul agent becomes unreachable from Druid nodes

### Prometheus Mapping (example)

If you use the Prometheus emitter, add these to your metrics config (dimension map) so our Consul metrics are exposed with useful labels:

```json
{
  "consul/announce/success": {
    "dimensions": ["role"],
    "type": "count",
    "help": "Druid Consul announce success"
  },
  "consul/announce/failure": {
    "dimensions": ["role"],
    "type": "count",
    "help": "Druid Consul announce failure"
  },
  "consul/healthcheck/failure": {
    "dimensions": [],
    "type": "count",
    "help": "Druid Consul TTL heartbeat failures"
  },
  "consul/watch/error": {
    "dimensions": ["role"],
    "type": "count",
    "help": "Druid Consul discovery watch errors"
  },
  "consul/watch/added": {
    "dimensions": ["role"],
    "type": "count",
    "help": "Druid nodes added by Consul watch"
  },
  "consul/watch/removed": {
    "dimensions": ["role"],
    "type": "count",
    "help": "Druid nodes removed by Consul watch"
  },
  "consul/leader/become": {
    "dimensions": ["lock"],
    "type": "count",
    "help": "Leader elected"
  },
  "consul/leader/stop": {
    "dimensions": ["lock"],
    "type": "count",
    "help": "Leadership lost/stopped"
  },
  "consul/leader/renew/fail": {
    "dimensions": ["lock"],
    "type": "count",
    "help": "Leader session renew failures"
  }
}
```

In Prometheus, you can then alert on spikes, for example:

```promql
sum by (role) (rate(druid_consul_watch_error[5m])) > 0
sum by (lock) (rate(druid_consul_leader_renew_fail[5m])) > 0
```

Note: Names are normalized by the Prometheus emitter; `consul/watch/error` becomes `druid_consul_watch_error` with your configured namespace.

### Production Checklist

- Run Consul servers across AZs within a single DC; all Druid nodes talk to the local agent in that DC
- Use ACL tokens with least-privilege policies (separate tokens for leaders vs others if desired)
- Enable TLS/mTLS for Consul HTTP API; distribute CA truststore and optional client keystores
- Set healthCheckInterval and deregisterAfter appropriate to network latency (e.g., 30s/180s for high latency)
- Increase watchSeconds (e.g., 120s) in large clusters to reduce Consul load
- Ensure time sync (NTP) on all nodes; session TTLs depend on accurate clocks
- Consider adding service tags (AZ, tier, version) for observability and filtering

## Limitations

- All Druid nodes must be able to reach the Consul agent
- Service metadata size is limited by Consul's limits (typically 512KB)
- Leader election paths must be unique per cluster (configure via `coordinatorLeaderLockPath` and `overlordLeaderLockPath` if running multiple clusters)
- No custom Druid metrics are emitted for Consul integration; monitoring relies on Consul's metrics and Druid logs
- For TLS configuration, Java keystores (PKCS12 or JKS) are recommended over PEM files for better compatibility

## Performance and Scalability

### Recommended Cluster Sizes

The Consul extension has been tested with:
- Small clusters: 1-10 Druid nodes
- Medium clusters: 10-50 Druid nodes
- Large clusters: 50+ Druid nodes

For very large clusters (100+ nodes), consider:
- Using Consul's multi-datacenter features
- Increasing `watchSeconds` to reduce query load on Consul
- Running Consul agents in client mode on each Druid node for better performance

### Network Latency Considerations

- **Low latency networks** (< 10ms): Default configuration works well
- **High latency networks** (> 50ms):
  - Increase `healthCheckInterval` to `PT30S` or higher
  - Increase `watchSeconds` to `PT120S` to reduce blocking query overhead
  - Monitor session renewal failures in logs

### Tuning for Large Clusters

For clusters with many nodes:
```properties
# Reduce health check frequency
druid.discovery.consul.healthCheckInterval=PT30S

# Increase deregister timeout
druid.discovery.consul.deregisterAfter=PT180S

# Increase watch timeout for blocking queries
druid.discovery.consul.watchSeconds=PT120S

# Keep watching indefinitely on failures
druid.discovery.consul.maxWatchRetries=-1

# Add service tags for observability/topology
druid.discovery.consul.serviceTags.az=us-east-1a
druid.discovery.consul.serviceTags.tier=hot
druid.discovery.consul.serviceTags.version=0.24.0
```

## Implementation Details

### Current Approach: Service Registration

The extension uses Consul's **Service Catalog** with the following design:

- Each Druid node registers as a Consul service
- Service name format: `{servicePrefix}-{nodeRole}` (e.g., `druid-prod-broker`)
- Full `DiscoveryDruidNode` JSON stored in service metadata
- TTL-based health checks with automatic updates
- Blocking queries for efficient change detection

**Advantages:**
- Native Consul integration
- Visible in Consul UI
- Built-in health checking
- Standard Consul patterns

**Limitations:**
- Service metadata size limits (~512KB typically)
- Requires regular health check updates

### Alternative Approaches

Other valid implementation patterns that could be considered:

#### 1. Key-Value Store Approach
Store node information in Consul's KV store:
```
/druid/{cluster}/{role}/{host:port} = DiscoveryDruidNode JSON
```

- Use Consul sessions for ephemeral keys (auto-cleanup)
- Watch KV prefix for changes
- More ZooKeeper-like behavior
- No metadata size limits

#### 2. Hybrid Approach
Combine services for discovery + KV for detailed metadata:
- Register service with minimal info
- Store full details in KV store
- Best of both worlds, but more complex

#### 3. Service + Tags
Use extensive Consul service tags for filtering:
- Faster queries with tag-based filtering
- Limited metadata in service itself
- Scales better for very large clusters

The current implementation (Service Catalog) was chosen for its simplicity, native Consul integration, and alignment with Consul best practices.

## Comparison with Other Discovery Methods

| Feature | ZooKeeper | Kubernetes | Consul |
|----------|-----------|------------|--------|
| Service Discovery | ✓ | ✓ | ✓ |
| Leader Election | ✓ | ✓ | ✓ |
| External Dependency | ZooKeeper cluster | Kubernetes cluster | Consul cluster |
| Health Checks | Session based | Liveness probes | TTL based |
| Leader Election | Ephemeral nodes | Lease API | Sessions + locks |
| Multi-cluster Support | ZK paths | Namespaces | Prefixes + paths |
| TLS/mTLS Support | ✓ | ✓ | ✓ |
| ACL/RBAC | ✓ | ✓ | ✓ |

## Testing

To test the Consul extension locally:

1. Start Consul in dev mode:
```bash
docker run -d --name=consul -p 8500:8500 consul:latest agent -dev -ui -client=0.0.0.0
```

2. Configure Druid to use Consul:
```properties
druid.discovery.type=consul
druid.discovery.consul.host=localhost
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid-test
```

3. Access Consul UI at `http://localhost:8500/ui` to see registered Druid services

## Troubleshooting

### Services not appearing in Consul

Check that:
- Druid extension is loaded (`druid.extensions.loadList` includes `druid-consul-extensions`)
- `druid.discovery.type=consul` is set
- Consul agent is reachable from Druid nodes
- ACL token has correct permissions (if ACL is enabled)
- Network connectivity between Druid and Consul is stable

### Health checks failing

Check that:
- Druid processes are running and healthy
- Health check interval is appropriate for your network latency
- Consul agent is not overloaded
- Time synchronization (NTP) is working across nodes

### Discovery not detecting changes

Check that:
- Watch duration is appropriate
- Network connectivity is stable
- Consul blocking queries are working correctly
- Check Druid logs for "Exception while watching for role" messages

### Leader election issues

**Problem: Frequent leader changes**
- Check network stability between nodes and Consul
- Increase `healthCheckInterval` to reduce sensitivity
- Review Consul agent logs for session expiration messages
- Check time synchronization across nodes

**Problem: No leader elected**
- Verify `druid.coordinator.selector.type=consul` and `druid.indexer.selector.type=consul` are set
- Check ACL token has KV and session permissions
- Verify `coordinatorLeaderLockPath` and `overlordLeaderLockPath` are accessible in Consul KV
- Check logs for "Failed to create session" or "Failed to acquire lock" errors

**Problem: Split-brain (multiple leaders)**
- This should not occur with Consul's strong consistency guarantees
- If observed, check for network partitions and Consul cluster health
- Verify all nodes are connecting to the same Consul cluster/datacenter

### TLS/mTLS connection failures

- Verify certificate paths are correct and files are readable by Druid process
- Check certificate validity (not expired, proper chain)
- Ensure hostname in certificate matches `druid.discovery.consul.host` (if `validateHostnames=true`)
- Test connectivity with openssl or curl first
- Check Consul server TLS configuration and logs
- Verify truststore contains the correct CA certificate
- For mTLS, ensure client certificate is properly signed and not expired

## Further Reading

- [Consul Service Discovery](https://www.consul.io/docs/discovery/services)
- [Consul Health Checks](https://www.consul.io/docs/discovery/checks)
- [Consul Sessions](https://www.consul.io/docs/dynamic-app-config/sessions)
- [Consul ACL System](https://www.consul.io/docs/security/acl)
- [Consul Leader Election](https://learn.hashicorp.com/tutorials/consul/application-leader-elections)
- [Druid HTTP-based Server View](../../operations/http-compression.md)
