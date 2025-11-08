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

# Consul Discovery Extension

This extension provides Consul-based service discovery for Apache Druid.

## Features

- Service registration with Consul
- Automatic service discovery
- Health checking via TTL checks
- Support for Consul ACL
- HTTP Basic Auth support
- Multi-datacenter support (DC usually maps to a region; election and discovery are DC-scoped)

## Usage

Add to your `extensions.loadList`:

```properties
druid.extensions.loadList=["druid-consul-extensions", ...]
```

Configure Consul discovery:

```properties
druid.discovery.type=consul
druid.discovery.consul.host=localhost
druid.discovery.consul.port=8500
druid.discovery.consul.servicePrefix=druid
```

## Documentation

See [docs/development/extensions-contrib/consul.md](../../docs/development/extensions-contrib/consul.md) for full documentation.

## Building

```bash
mvn clean install -DskipTests
```

## Testing

Unit tests:
```bash
mvn test
```

Integration tests (requires running Consul):
```bash
docker run -d --name=consul -p 8500:8500 consul:latest agent -dev -ui -client=0.0.0.0
mvn test -Dtest=ConsulAnnouncerAndDiscoveryIntTest
```

## License

Apache License, Version 2.0
