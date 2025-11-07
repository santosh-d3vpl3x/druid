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

## Use Cases

This extension is useful when:
- Your infrastructure already uses Consul for service discovery
- You want to run Druid without ZooKeeper entirely (this extension provides both service discovery and leader election)
- You want a lightweight service discovery and coordination mechanism with health checking

## Configuration

To use this extension, make sure to [include](../../configuration/extensions.md#loading-extensions) `druid-consul-extensions` in the extensions load list.

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

|Property|Possible Values|Description|Default|Required|
|--------|---------------|-----------|-------|--------|
|`druid.discovery.consul.host`|String|Consul agent hostname or IP address.|`localhost`|No|
|`druid.discovery.consul.port`|Integer|Consul agent HTTP API port.|`8500`|No|
|`druid.discovery.consul.servicePrefix`|String|Prefix for Consul service names; namespaces clusters.|None|Yes|
|`druid.discovery.consul.aclToken`|String|Consul ACL token for authentication.|None|No|
|`druid.discovery.consul.datacenter`|String|Consul datacenter for registration and discovery.|Default datacenter|No|
|`druid.discovery.consul.enableTls`|Boolean|Enable HTTPS/TLS for Consul communication.|`false`|No|
|`druid.discovery.consul.tlsCertificatePath`|String|Path to client certificate file (PEM or PKCS12).|None|No|
|`druid.discovery.consul.tlsKeyPath`|String|Path to client private key file for TLS.|None|No|
|`druid.discovery.consul.tlsCaCertPath`|String|Path to CA certificate for server verification.|None|No|
|`druid.discovery.consul.tlsVerifyHostname`|Boolean|Verify Consul server hostname in certificate.|`true`|No|
|`druid.discovery.consul.basicAuthUser`|String|Username for HTTP basic authentication.|None|No|
|`druid.discovery.consul.basicAuthPassword`|String|Password for HTTP basic authentication.|None|No|
|`druid.discovery.consul.healthCheckInterval`|ISO8601 Duration|Update interval for Consul health checks.|`PT10S`|No|
|`druid.discovery.consul.deregisterAfter`|ISO8601 Duration|Deregister service after health check fails.|`PT90S`|No|
|`druid.discovery.consul.watchSeconds`|ISO8601 Duration|Blocking query timeout for service changes.|`PT60S`|No|
|`druid.discovery.consul.maxWatchRetries`|Long|Max watch retries before giving up.|`Long.MAX_VALUE`|No|
|`druid.discovery.consul.watchRetryDelay`|ISO8601 Duration|Wait time before retrying failed watch.|`PT10S`|No|
|`druid.discovery.consul.coordinatorLeaderLockPath`|String|Consul KV path for Coordinator leader lock.|`druid/leader/coordinator`|No|
|`druid.discovery.consul.overlordLeaderLockPath`|String|Consul KV path for Overlord leader lock.|`druid/leader/overlord`|No|

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

For TLS-enabled Consul with certificate authentication:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8501
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/etc/druid/certs/consul-ca.pem
druid.discovery.consul.tlsCertificatePath=/etc/druid/certs/druid-client.pem
druid.discovery.consul.tlsKeyPath=/etc/druid/certs/druid-client-key.pem
druid.discovery.consul.tlsVerifyHostname=true
druid.discovery.consul.aclToken=your-secret-acl-token
```

For Consul with basic authentication:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=consul.example.com
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid-prod
druid.discovery.consul.basicAuthUser=druid
druid.discovery.consul.basicAuthPassword=secret-password
```

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

For encrypted communication and server verification:

```properties
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/path/to/consul-ca.pem
```

### 3. Mutual TLS (mTLS) Authentication

For strongest security, use client certificates:

```properties
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/path/to/consul-ca.pem
druid.discovery.consul.tlsCertificatePath=/path/to/client-cert.pem
druid.discovery.consul.tlsKeyPath=/path/to/client-key.pem
druid.discovery.consul.tlsVerifyHostname=true
```

**Note:** Certificates can be in PEM or PKCS12 format. For PEM private keys in production, you may need to add BouncyCastle to the extension's classpath:

```bash
# Download BouncyCastle jars and place in extension directory
cd $DRUID_HOME/extensions/druid-consul-extensions/
wget https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk15on/1.70/bcprov-jdk15on-1.70.jar
wget https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk15on/1.70/bcpkix-jdk15on-1.70.jar
```

Alternatively, use PKCS12 format which is natively supported by Java.

### 4. Basic Authentication

For simple HTTP basic auth (less common):

```properties
druid.discovery.consul.basicAuthUser=username
druid.discovery.consul.basicAuthPassword=password
```

### 5. Combined Authentication

You can combine methods for defense-in-depth:

```properties
# TLS + ACL Token
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/path/to/ca.pem
druid.discovery.consul.aclToken=your-token

# mTLS + ACL Token (most secure)
druid.discovery.consul.enableTls=true
druid.discovery.consul.tlsCaCertPath=/path/to/ca.pem
druid.discovery.consul.tlsCertificatePath=/path/to/cert.pem
druid.discovery.consul.tlsKeyPath=/path/to/key.pem
druid.discovery.consul.aclToken=your-token
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

Currently, the extension does not emit custom Druid metrics. Monitoring relies on:
1. Consul's built-in metrics and health checks
2. Druid application logs
3. Consul UI for visualizing service health and registrations

**Recommended Alerts:**
- Alert when Druid services disappear from Consul catalog
- Alert when health checks fail for extended periods
- Alert on frequent leader election changes (indicates instability)
- Alert when Consul agent becomes unreachable from Druid nodes

## Limitations

- All Druid nodes must be able to reach the Consul agent
- Service metadata size is limited by Consul's limits (typically 512KB)
- Leader election paths must be unique per cluster (configure via `coordinatorLeaderLockPath` and `overlordLeaderLockPath` if running multiple clusters)
- No custom Druid metrics are emitted for Consul integration; monitoring relies on Consul's metrics and Druid logs
- For TLS with PEM-encoded private keys, you may need to add BouncyCastle library to the classpath for proper key parsing:
  ```xml
  <!-- Add to extension's dependencies if needed -->
  <dependency>
    <groupId>org.bouncycastle</groupId>
    <artifactId>bcpkix-jdk15on</artifactId>
    <version>1.70</version>
  </dependency>
  ```

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

| Feature               | ZooKeeper         | Kubernetes | Consul           |
|-----------------------|-------------------|------------|------------------|
| Service Discovery     | ✓                 | ✓ | ✓                |
| Leader Election       | ✓                 | ✓ | ✓                |
| External Dependency   | ZooKeeper cluster | Kubernetes cluster | Consul cluster   |
| Health Checks         | Session based     | Liveness probes | TTL based        |
| Leader Election       | Ephemeral nodes   | Lease API | Sessions + locks |
| Multi-cluster Support | ZK paths          | Namespaces | Prefixes + paths |
| TLS/mTLS Support      | ✓                 | ✓ | ✓                |
| ACL/RBAC              | ✓                 | ✓ | ✓                |

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
- Check certificate validity (not expired)
- Ensure CA certificate matches Consul server certificate
- For PEM private keys, ensure BouncyCastle library is on classpath
- Enable Java SSL debugging: `-Djavax.net.debug=ssl` to diagnose handshake issues

### Migration from ZooKeeper

When migrating from ZooKeeper to Consul:

1. Prepare the new configuration with Consul settings
2. Stop all Druid services
3. Update configuration on all nodes
4. Start Consul cluster (if not already running)
5. Start Druid services in this order:
   - Coordinator (verify leader election in Consul UI)
   - Overlord (verify leader election in Consul UI)
   - Historical nodes
   - Broker nodes
   - MiddleManager/Indexer nodes
6. Verify all services appear in Consul catalog
7. Monitor logs for any discovery or leader election errors

**Note:** There is no automatic data migration from ZooKeeper to Consul. Metadata (segments, rules, etc.) is stored in the metadata database, not in ZooKeeper/Consul, so no migration is needed for that data.

## Upgrades and Rolling Restarts

### Upgrading the Extension

When upgrading the Consul extension to a newer version:

1. Stop all Druid services
2. Replace the extension files in `$DRUID_HOME/extensions/druid-consul-extensions/`
3. Start services as described in the Migration section above

### Rolling Restarts

For rolling restarts with Consul discovery enabled:

**Safe approach:**
1. Restart non-leader Coordinators/Overlords first (if running multiple)
2. Restart Historical nodes one at a time
3. Restart Broker nodes one at a time
4. Restart MiddleManager/Indexer nodes
5. Restart leader Coordinator/Overlord last

**Important:** During rolling restarts:
- Services will deregister when stopped and re-register when started
- Other nodes will detect the changes via Consul watch queries
- Leader election will only be affected when restarting the leader node
- Expect brief leadership transfer during leader restart (15-45 seconds)

### Rollback Procedure

To rollback from Consul to ZooKeeper:

1. Stop all Druid services
2. Update configuration files to remove Consul settings and restore ZooKeeper settings:
   ```properties
   # Remove these
   #druid.discovery.type=consul
   #druid.coordinator.selector.type=consul
   #druid.indexer.selector.type=consul
   
   # Restore these
   druid.zk.service.host=<zookeeper-host>:2181
   druid.zk.paths.base=/druid
   ```
3. Remove `druid-consul-extensions` from `druid.extensions.loadList`
4. Start Druid services in normal order
5. Verify connectivity to ZooKeeper in logs

## Further Reading

- [Consul Service Discovery](https://www.consul.io/docs/discovery/services)
- [Consul Health Checks](https://www.consul.io/docs/discovery/checks)
- [Consul Sessions](https://www.consul.io/docs/dynamic-app-config/sessions)
- [Consul ACL System](https://www.consul.io/docs/security/acl)
- [Consul Leader Election](https://learn.hashicorp.com/tutorials/consul/application-leader-elections)
- [Druid HTTP-based Server View](../../operations/http-compression.md)

