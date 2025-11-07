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
- You want to run Druid without ZooKeeper for node discovery (ZooKeeper is still required for coordinator/overlord leader election unless using Kubernetes)
- You want a lightweight service discovery mechanism with health checking

## Configuration

To use this extension, make sure to [include](../../configuration/extensions.md#loading-extensions) `druid-consul-extensions` in the extensions load list.

This extension works together with HTTP-based segment and task management in Druid. The following configurations must be set on all Druid nodes:

```
druid.serverview.type=http
druid.indexer.runner.type=httpRemote
druid.discovery.type=consul
```

**Note:** ZooKeeper is still required for Coordinator and Overlord leader election. To completely remove ZooKeeper dependency, use the Kubernetes extension which provides both service discovery and leader election.

### Properties

|Property|Possible Values|Description|Default|Required|
|--------|---------------|-----------|-------|--------|
|`druid.discovery.consul.host`|String|Consul agent hostname or IP address.|`localhost`|No|
|`druid.discovery.consul.port`|Integer|Consul agent HTTP API port.|`8500`|No|
|`druid.discovery.consul.servicePrefix`|String|Prefix for Consul service names. Used to namespace multiple Druid clusters in the same Consul cluster.|None|Yes|
|`druid.discovery.consul.aclToken`|String|Consul ACL token for authentication. Required if Consul ACL is enabled.|None|No|
|`druid.discovery.consul.datacenter`|String|Consul datacenter to use for service registration and discovery.|Default datacenter|No|
|`druid.discovery.consul.healthCheckInterval`|ISO8601 Duration|How often to update Consul health checks (TTL checks).|`PT10S`|No|
|`druid.discovery.consul.deregisterAfter`|ISO8601 Duration|How long after health check fails before Consul deregisters the service.|`PT90S`|No|
|`druid.discovery.consul.watchSeconds`|ISO8601 Duration|How long to block when watching for service changes (Consul blocking query duration).|`PT60S`|No|
|`druid.discovery.consul.maxWatchRetries`|Long|Maximum number of watch retries before giving up. Set to `Long.MAX_VALUE` for unlimited.|`Long.MAX_VALUE`|No|
|`druid.discovery.consul.watchRetryDelay`|ISO8601 Duration|How long to wait before retrying after a watch error.|`PT10S`|No|

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

# ZooKeeper still needed for leader election
druid.zk.service.host=zk-1:2181,zk-2:2181,zk-3:2181
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

## Requirements

- Consul 1.0.0 or higher
- Network connectivity from all Druid nodes to Consul agent
- If using Consul ACL, appropriate ACL token with permissions to:
  - Register and deregister services
  - Read service catalog
  - Update health checks

## Consul ACL Permissions

If Consul ACL is enabled, the ACL token must have the following permissions:

```hcl
service "{servicePrefix}-" {
  policy = "write"
}

service_prefix "" {
  policy = "read"
}
```

## Monitoring

Monitor the following in your Consul cluster:
- Service registrations for each Druid node role
- Health check status for all Druid services
- Consul agent connectivity

Check Druid logs for:
- `Successfully announced DiscoveryDruidNode` - Node registered successfully
- `Failed to announce` - Registration errors
- `Exception while watching for role` - Discovery errors

## Limitations

- This extension provides service discovery only, not leader election
- ZooKeeper is still required for Coordinator and Overlord leader election
- All Druid nodes must be able to reach the Consul agent
- Service metadata size is limited by Consul's limits (typically 512KB)

## Comparison with Other Discovery Methods

| Feature | ZooKeeper | Kubernetes | Consul |
|---------|-----------|------------|--------|
| Service Discovery | ✓ | ✓ | ✓ |
| Leader Election | ✓ | ✓ | ✗ |
| External Dependency | ZooKeeper cluster | Kubernetes cluster | Consul cluster |
| Health Checking | Session-based | Liveness probes | TTL checks |
| Multi-cluster Support | Via ZK paths | Via namespaces | Via service prefixes |

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

### Health checks failing

Check that:
- Druid processes are running and healthy
- Health check interval is appropriate for your network latency
- Consul agent is not overloaded

### Discovery not detecting changes

Check that:
- Watch duration is appropriate
- Network connectivity is stable
- Consul blocking queries are working correctly

## Further Reading

- [Consul Service Discovery](https://www.consul.io/docs/discovery/services)
- [Consul Health Checks](https://www.consul.io/docs/discovery/checks)
- [Consul ACL System](https://www.consul.io/docs/security/acl)
