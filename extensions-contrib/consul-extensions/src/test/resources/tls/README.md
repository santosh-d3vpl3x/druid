# Consul TLS/mTLS Testing

This directory contains test certificates and configurations for testing Consul with TLS and mTLS.

**Note:** The TLS/mTLS tests are marked with `@Ignore` and will not run in CI. 
They require a manually started Consul instance with TLS enabled.

## Setup

### 1. Generate Test Certificates

```bash
cd extensions-contrib/consul-extensions/src/test/resources/tls
./generate-test-certs.sh
```

This creates:
- `ca-cert.pem` / `ca-key.pem` - Certificate Authority
- `consul-server-cert.pem` / `consul-server-key.pem` - Consul server certificate
- `client-cert.pem` / `client-key.pem` - Client certificate for mTLS
- `truststore.p12` - Java truststore (CA cert)
- `client.p12` - Java keystore (client cert)

### 2. Start Consul

**Choose one mode:**

#### Option A: TLS-only (Server verification, NO client certs)
```bash
./start-consul-tls.sh
```

This starts Consul with:
- HTTPS on port 8501
- `verify_incoming: false` - Client certificates NOT required
- `verify_outgoing: false` - For server-to-server (not relevant in dev mode)

#### Option B: mTLS (Requires client certificates)
```bash
./start-consul-mtls.sh
```

This starts Consul with:
- HTTPS on port 8501
- `verify_incoming: true` - Client certificates REQUIRED (mTLS)
- `verify_outgoing: true` - Verifies server certificates

## Running Tests

### Testing with TLS-only Consul

Start Consul in TLS-only mode:
```bash
./start-consul-tls.sh
```

Run truststore-only test (should PASS):
```bash
cd /Users/santosh/workspace/druid
mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithTruststore \
  -pl extensions-contrib/consul-extensions \
  -P skip-static-checks
```
**Expected**: ✅ PASS - Connects with just truststore, no client cert needed

Run mTLS test (should also PASS):
```bash
mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithMTLS \
  -pl extensions-contrib/consul-extensions \
  -P skip-static-checks
```
**Expected**: ✅ PASS - Client cert provided but not required by server

---

### Testing with mTLS Consul

Start Consul in mTLS mode:
```bash
./start-consul-mtls.sh
```

Run mTLS test (should PASS):
```bash
mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithMTLS \
  -pl extensions-contrib/consul-extensions \
  -P skip-static-checks
```
**Expected**: ✅ PASS - Successfully authenticates with client certificate

Run truststore-only test (should FAIL):
```bash
mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithTruststore \
  -pl extensions-contrib/consul-extensions \
  -P skip-static-checks
```
**Expected**: ❌ FAIL with `SSLHandshakeException: handshake_failure`

This proves that:
1. Consul is correctly requiring client certificates
2. Our truststore-only config doesn't provide a client cert
3. The mTLS enforcement is working as expected

## Configuration Files

- `consul-config-tls-only.json` - TLS only (`verify_incoming: false`)
- `consul-config-mtls.json` - mTLS (`verify_incoming: true`)
- `consul-config.json` - Symlink/default (currently same as mtls)

## Scripts

- `start-consul-tls.sh` - Start Consul in TLS-only mode
- `start-consul-mtls.sh` - Start Consul in mTLS mode
- `generate-test-certs.sh` - Generate all test certificates

## Verifying Manually

### TLS-only Mode

```bash
# Without client cert - SUCCESS ✅
curl --cacert ca-cert.pem https://localhost:8501/v1/status/leader

# With client cert - Also SUCCESS ✅ (cert ignored by server)
curl --cacert ca-cert.pem \
     --cert client-cert.pem \
     --key client-key.pem \
     https://localhost:8501/v1/status/leader
```

### mTLS Mode

```bash
# With client cert - SUCCESS ✅
curl --cacert ca-cert.pem \
     --cert client-cert.pem \
     --key client-key.pem \
     https://localhost:8501/v1/status/leader

# Without client cert - FAILURE ❌
curl --cacert ca-cert.pem https://localhost:8501/v1/status/leader
# Error: SSL routines:ST_OK:reason(1116)
```

## Test Matrix

| Consul Mode | Test | Client Cert | Expected Result |
|-------------|------|-------------|-----------------|
| TLS-only | testTLSConnectionWithTruststore | No | ✅ PASS |
| TLS-only | testTLSConnectionWithMTLS | Yes | ✅ PASS |
| mTLS | testTLSConnectionWithTruststore | No | ❌ FAIL |
| mTLS | testTLSConnectionWithMTLS | Yes | ✅ PASS |

## Cleanup

```bash
docker rm -f consul-tls
rm -rf /tmp/consul-tls-test /tmp/consul-mtls-test
```
