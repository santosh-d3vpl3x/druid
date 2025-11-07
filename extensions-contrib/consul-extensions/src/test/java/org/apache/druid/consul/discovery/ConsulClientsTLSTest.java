/*
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
 */

package org.apache.druid.consul.discovery;

import com.ecwid.consul.v1.ConsulClient;
import org.apache.druid.https.SSLClientConfig;
import org.apache.druid.java.util.common.StringUtils;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.net.Socket;

/**
 * Integration test for Consul TLS/mTLS connectivity.
 *
 * These tests require a running Consul instance with TLS/mTLS enabled.
 * They are ignored by default to avoid CI failures.
 *
 * To run these tests manually:
 *
 * 1. Generate test certificates (one-time setup):
 *    cd extensions-contrib/consul-extensions/src/test/resources/tls
 *    ./generate-test-certs.sh
 *
 *    This creates:
 *    - ca-cert.pem / ca-key.pem (Certificate Authority)
 *    - consul-server-cert.pem / consul-server-key.pem (Consul server cert)
 *    - client-cert.pem / client-key.pem (Client cert for mTLS)
 *    - truststore.p12 (Java truststore with CA cert, password: changeit)
 *    - client.p12 (Java keystore with client cert, password: changeit)
 *
 * 2. Start Consul in desired mode:
 *    ./start-consul-tls.sh    (TLS-only, no client certs required)
 *    ./start-consul-mtls.sh   (mTLS, requires client certificates)
 *
 * 3. Run specific tests:
 *    cd /path/to/druid
 *    mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithTruststore \
 *      -pl extensions-contrib/consul-extensions -P skip-static-checks
 *    mvn test -Dtest=ConsulClientsTLSTest#testTLSConnectionWithMTLS \
 *      -pl extensions-contrib/consul-extensions -P skip-static-checks
 *
 * Note: For testTLSConnectionWithTruststore to pass, use ./start-consul-tls.sh
 *       For testTLSConnectionWithMTLS to pass, use ./start-consul-mtls.sh
 */
@Ignore("Requires running Consul with TLS/mTLS - manual test only")
public class ConsulClientsTLSTest
{
  private static final String CONSUL_HOST = "localhost";
  private static final int CONSUL_HTTPS_PORT = 8501;

  private ConsulClient consulClient;

  @Before
  public void setUp()
  {
    // Check if Consul TLS is running
    boolean consulRunning = isPortOpen(CONSUL_HOST, CONSUL_HTTPS_PORT);
    Assume.assumeTrue(
        "Consul TLS not running on " + CONSUL_HOST + ":" + CONSUL_HTTPS_PORT + ". " +
        "Start it with: src/test/resources/tls/start-consul-tls.sh",
        consulRunning
    );
  }

  @After
  public void tearDown()
  {
    consulClient = null;
  }

  @Test
  public void testTLSConnectionWithTruststore() throws Exception
  {
    // Get path to test truststore
    File truststoreFile = new File("src/test/resources/tls/truststore.p12");
    Assert.assertTrue(
        "Truststore not found. Run: src/test/resources/tls/generate-test-certs.sh",
        truststoreFile.exists()
    );

    // Create config with TLS using Jackson-style setters
    SSLClientConfig sslConfig = new SSLClientConfig();
    setField(sslConfig, "protocol", "TLSv1.2");
    setField(sslConfig, "trustStoreType", "PKCS12");
    setField(sslConfig, "trustStorePath", truststoreFile.getAbsolutePath());
    setField(sslConfig, "trustStorePasswordProvider", (org.apache.druid.metadata.PasswordProvider) () -> "changeit");
    setField(sslConfig, "validateHostnames", false);

    ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
        CONSUL_HOST,
        CONSUL_HTTPS_PORT,
        "druid-tls-test",
        null,                               // aclToken
        null,                               // datacenter
        null,                               // coordinatorLeaderLockPath
        null,                               // overlordLeaderLockPath
        sslConfig,                          // sslClientConfig
        null,                               // basicAuthUser
        null,                               // basicAuthPassword
        Duration.millis(10000),             // healthCheckInterval
        Duration.millis(60000),             // deregisterAfter
        Duration.millis(10000),             // watchSeconds
        null,                               // maxWatchRetries
        Duration.millis(10000)              // watchRetryDelay
    );

    // This is the critical test - does ConsulClients.create() properly
    // configure TLS using reflection to call setHttpClient with the
    // correct HttpClient.class parameter (not Object.class)?
    System.out.println("Creating Consul client with TLS configuration...");
    System.out.println("  Host: " + config.getHost());
    System.out.println("  Port: " + config.getPort());
    System.out.println("  SSL Config: " + config.getSslClientConfig());
    System.out.println("  Truststore path: " + config.getSslClientConfig().getTrustStorePath());
    consulClient = ConsulClients.create(config);
    Assert.assertNotNull("ConsulClient should be created", consulClient);
    System.out.println("Client created: " + consulClient.getClass().getName());

    // Try to make an actual API call over TLS
    System.out.println("Making API call to verify TLS connection...");
    com.ecwid.consul.v1.Response<String> response = consulClient.getStatusLeader();
    String leader = response.getValue();
    System.out.println("Successfully connected via TLS! Leader: " + leader);

    // Leader might be empty in dev mode, but call should not throw
    Assert.assertNotNull("Leader response should not be null", leader);

    System.out.println("✅ TLS connection validated successfully!");
  }

  @Test
  public void testTLSConnectionWithMTLS() throws Exception
  {
    // Get paths to test keystores
    File truststoreFile = new File("src/test/resources/tls/truststore.p12");
    File keystoreFile = new File("src/test/resources/tls/client.p12");

    Assume.assumeTrue(
        "Keystores not found. Run: src/test/resources/tls/generate-test-certs.sh",
        truststoreFile.exists() && keystoreFile.exists()
    );

    // Create config with mTLS (client certificate)
    SSLClientConfig sslConfig = new SSLClientConfig();
    setField(sslConfig, "protocol", "TLSv1.2");
    setField(sslConfig, "trustStoreType", "PKCS12");
    setField(sslConfig, "trustStorePath", truststoreFile.getAbsolutePath());
    setField(sslConfig, "trustStorePasswordProvider", (org.apache.druid.metadata.PasswordProvider) () -> "changeit");
    setField(sslConfig, "keyStoreType", "PKCS12");
    setField(sslConfig, "keyStorePath", keystoreFile.getAbsolutePath());
    setField(sslConfig, "certAlias", "client");
    setField(sslConfig, "keyStorePasswordProvider", (org.apache.druid.metadata.PasswordProvider) () -> "changeit");
    setField(sslConfig, "keyManagerPasswordProvider", (org.apache.druid.metadata.PasswordProvider) () -> "changeit");
    setField(sslConfig, "validateHostnames", false);

    ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
        CONSUL_HOST,
        CONSUL_HTTPS_PORT,
        "druid-mtls-test",
        null,
        null,
        null,
        null,
        sslConfig,
        null,
        null,
        Duration.millis(10000),
        Duration.millis(60000),
        Duration.millis(10000),
        null,
        Duration.millis(10000)
    );

    // Test with client certificate (mTLS)
    System.out.println("Creating Consul client with mTLS configuration...");
    consulClient = ConsulClients.create(config);
    Assert.assertNotNull("ConsulClient should be created", consulClient);

    System.out.println("Making API call to verify mTLS connection...");
    com.ecwid.consul.v1.Response<String> response = consulClient.getStatusLeader();
    String leader = response.getValue();
    System.out.println("Successfully connected via mTLS! Leader: " + leader);

    Assert.assertNotNull("Leader response should not be null", leader);

    System.out.println("✅ mTLS connection validated successfully!");
  }

  @Test
  public void testTLSConnectionFailsWithoutTruststore()
  {
    // Config without TLS should fail to connect to HTTPS endpoint
    ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
        CONSUL_HOST,
        CONSUL_HTTPS_PORT,
        "druid-no-tls-test",
        null,
        null,
        null,
        null,
        null,  // No SSL config
        null,
        null,
        Duration.millis(10000),
        Duration.millis(60000),
        Duration.millis(10000),
        null,
        Duration.millis(10000)
    );

    System.out.println("Creating Consul client WITHOUT TLS configuration (should fail)...");
    consulClient = ConsulClients.create(config);

    // Should create client, but API call should fail
    try {
      consulClient.getStatusLeader();
      Assert.fail("Should have thrown exception when connecting to HTTPS without TLS config");
    }
    catch (Exception e) {
      System.out.println("✅ Expected failure without TLS config: " + e.getClass().getSimpleName());
      // This is expected - connection should fail without proper TLS setup
    }
  }

  private boolean isPortOpen(String host, int port)
  {
    try (Socket socket = new Socket(host, port)) {
      return true;
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Helper to set private fields on SSLClientConfig since it doesn't have setters
   */
  private void setField(Object obj, String fieldName, Object value) throws Exception
  {
    java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(obj, value);
  }
}
