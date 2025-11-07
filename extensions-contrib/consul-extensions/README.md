# Consul Discovery Extension

This extension provides Consul-based service discovery for Apache Druid.

## Features

- Service registration with Consul
- Automatic service discovery
- Health checking via TTL checks
- Support for Consul ACL
- Multi-datacenter support

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
