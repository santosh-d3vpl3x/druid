#!/bin/bash
# Start Consul with mTLS (requires client certificates)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONSUL_DATA_DIR="/tmp/consul-mtls-test"

echo "Stopping any existing Consul containers..."
docker rm -f consul-tls 2>/dev/null || true

echo "Cleaning up old data directory..."
rm -rf "$CONSUL_DATA_DIR"
mkdir -p "$CONSUL_DATA_DIR"

echo "Starting Consul with mTLS (requires client certificates)..."
docker run -d \
  --name consul-tls \
  -p 8501:8501 \
  -v "$SCRIPT_DIR:/tls:ro" \
  -v "$SCRIPT_DIR/consul-config-mtls.json:/consul/config/consul-config.json:ro" \
  -v "$CONSUL_DATA_DIR:/consul/data" \
  hashicorp/consul:latest \
  agent -dev -config-file=/consul/config/consul-config.json

echo "Waiting for Consul to start..."
sleep 10

echo "Testing Consul mTLS connection WITH client cert (should work)..."
curl --cacert "$SCRIPT_DIR/ca-cert.pem" \
     --cert "$SCRIPT_DIR/client-cert.pem" \
     --key "$SCRIPT_DIR/client-key.pem" \
     https://localhost:8501/v1/status/leader

echo ""
echo "✅ Consul with mTLS is running!"
echo "   HTTPS endpoint: https://localhost:8501"
echo "   Mode: mTLS (verify_incoming=true)"
echo "   CA certificate: $SCRIPT_DIR/ca-cert.pem"
echo "   Client cert: $SCRIPT_DIR/client-cert.pem"
echo "   Client key: $SCRIPT_DIR/client-key.pem"
echo "   Truststore: $SCRIPT_DIR/truststore.p12 (password: changeit)"
echo "   Client keystore: $SCRIPT_DIR/client.p12 (password: changeit)"
echo ""
echo "Client certificates ARE required."
echo ""
echo "To stop: docker rm -f consul-tls"
