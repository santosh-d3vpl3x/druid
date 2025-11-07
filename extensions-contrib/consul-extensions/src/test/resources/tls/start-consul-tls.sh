#!/bin/bash
# Start Consul with TLS-only (server certificate verification, NO client certs required)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONSUL_DATA_DIR="/tmp/consul-tls-test"

echo "Stopping any existing Consul containers..."
docker rm -f consul-tls 2>/dev/null || true

echo "Cleaning up old data directory..."
rm -rf "$CONSUL_DATA_DIR"
mkdir -p "$CONSUL_DATA_DIR"

echo "Starting Consul with TLS-only (NO mTLS)..."
docker run -d \
  --name consul-tls \
  -p 8501:8501 \
  -v "$SCRIPT_DIR:/tls:ro" \
  -v "$SCRIPT_DIR/consul-config-tls-only.json:/consul/config/consul-config.json:ro" \
  -v "$CONSUL_DATA_DIR:/consul/data" \
  hashicorp/consul:latest \
  agent -dev -config-file=/consul/config/consul-config.json

echo "Waiting for Consul to start..."
sleep 10

echo "Testing Consul TLS connection WITHOUT client cert (should work)..."
curl --cacert "$SCRIPT_DIR/ca-cert.pem" \
     https://localhost:8501/v1/status/leader

echo ""
echo "✅ Consul with TLS-only is running!"
echo "   HTTPS endpoint: https://localhost:8501"
echo "   Mode: TLS-only (verify_incoming=false)"
echo "   CA certificate: $SCRIPT_DIR/ca-cert.pem"
echo "   Truststore: $SCRIPT_DIR/truststore.p12 (password: changeit)"
echo ""
echo "Client certificates are NOT required."
echo ""
echo "To stop: docker rm -f consul-tls"
