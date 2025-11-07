#!/bin/bash
# Generate test certificates for TLS validation

set -e

cd "$(dirname "$0")"

echo "Generating CA certificate..."
openssl req -x509 -newkey rsa:2048 -days 365 -nodes \
  -keyout ca-key.pem \
  -out ca-cert.pem \
  -subj "/CN=Test CA/O=Druid Test/C=US"

echo "Generating Consul server certificate..."
openssl req -newkey rsa:2048 -nodes \
  -keyout consul-server-key.pem \
  -out consul-server-req.pem \
  -subj "/CN=localhost/O=Druid Test/C=US"

# Create config for SAN (Subject Alternative Names)
cat > consul-server-ext.cnf <<EOF
subjectAltName = DNS:localhost,IP:127.0.0.1
EOF

openssl x509 -req -in consul-server-req.pem \
  -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out consul-server-cert.pem \
  -days 365 \
  -extfile consul-server-ext.cnf

echo "Generating client certificate..."
openssl req -newkey rsa:2048 -nodes \
  -keyout client-key.pem \
  -out client-req.pem \
  -subj "/CN=druid-client/O=Druid Test/C=US"

openssl x509 -req -in client-req.pem \
  -CA ca-cert.pem -CAkey ca-key.pem -CAcreateserial \
  -out client-cert.pem \
  -days 365

echo "Creating Java truststore..."
keytool -import -noprompt \
  -file ca-cert.pem \
  -alias ca \
  -keystore truststore.jks \
  -storepass changeit

echo "Creating Java keystore with client certificate..."
openssl pkcs12 -export \
  -in client-cert.pem \
  -inkey client-key.pem \
  -out client.p12 \
  -name client \
  -passout pass:changeit

keytool -importkeystore \
  -srckeystore client.p12 -srcstoretype PKCS12 -srcstorepass changeit \
  -destkeystore keystore.jks -deststoretype JKS -deststorepass changeit \
  -noprompt

echo "Creating PKCS12 versions..."
keytool -importkeystore \
  -srckeystore truststore.jks -srcstoretype JKS -srcstorepass changeit \
  -destkeystore truststore.p12 -deststoretype PKCS12 -deststorepass changeit

echo "Test certificates generated successfully!"
echo "Files created:"
echo "  - ca-cert.pem (CA certificate)"
echo "  - consul-server-cert.pem, consul-server-key.pem (Consul server)"
echo "  - client-cert.pem, client-key.pem (Client)"
echo "  - truststore.jks, truststore.p12 (Java truststores)"
echo "  - keystore.jks, client.p12 (Java keystores with client cert)"
