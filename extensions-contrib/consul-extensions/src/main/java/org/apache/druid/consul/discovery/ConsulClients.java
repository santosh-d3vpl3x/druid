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
import com.ecwid.consul.v1.ConsulRawClient;
import org.apache.druid.https.SSLClientConfig;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.server.security.TLSUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import javax.net.ssl.SSLContext;

/**
 * Helper for constructing a ConsulClient with TLS support.
 *
 * This class properly configures HTTPS transport for the Ecwid Consul client
 * using Druid's standard TLS infrastructure.
 */
final class ConsulClients
{
  private static final Logger LOGGER = new Logger(ConsulClients.class);

  private ConsulClients()
  {
  }

  static ConsulClient create(ConsulDiscoveryConfig config)
  {
    SSLClientConfig sslConfig = config.getSslClientConfig();

    // If TLS is configured, use HTTPS
    if (sslConfig != null && sslConfig.getTrustStorePath() != null) {
      try {
        // Build SSLContext using Druid's TLSUtils
        SSLContext sslContext = buildSslContext(sslConfig);
        
        // Create custom HttpClient with SSL support
        HttpClient httpClient = createHttpClientWithSslContext(sslContext);

        // Use "https://" prefix to signal HTTPS to Consul client
        String httpsHost = "https://" + config.getHost();

        // Use constructor that accepts custom HttpClient
        ConsulRawClient rawClient = new ConsulRawClient(httpsHost, config.getPort(), httpClient);

        LOGGER.info("Created Consul client with HTTPS");
        return new ConsulClient(rawClient);
      }
      catch (Exception e) {
        LOGGER.error(e, "Failed to configure TLS, falling back to plain HTTP");
      }
    }

    // No TLS or TLS failed - use plain HTTP
    return new ConsulClient(config.getHost(), config.getPort());
  }

  /**
   * Build SSLContext from SSLClientConfig using Druid's standard TLS infrastructure.
   */
  private static SSLContext buildSslContext(SSLClientConfig config)
  {
    try {
      return new TLSUtils.ClientSSLContextBuilder()
          .setProtocol(config.getProtocol())
          .setTrustStoreType(config.getTrustStoreType())
          .setTrustStorePath(config.getTrustStorePath())
          .setTrustStoreAlgorithm(config.getTrustStoreAlgorithm())
          .setTrustStorePasswordProvider(config.getTrustStorePasswordProvider())
          .setKeyStoreType(config.getKeyStoreType())
          .setKeyStorePath(config.getKeyStorePath())
          .setKeyStoreAlgorithm(config.getKeyManagerFactoryAlgorithm())
          .setCertAlias(config.getCertAlias())
          .setKeyStorePasswordProvider(config.getKeyStorePasswordProvider())
          .setKeyManagerFactoryPasswordProvider(config.getKeyManagerPasswordProvider())
          .setValidateHostnames(config.getValidateHostnames())
          .build();
    }
    catch (Exception e) {
      LOGGER.error(e, "Failed to build SSLContext from SSLClientConfig");
      throw new IllegalStateException("Failed to build SSLContext", e);
    }
  }

  /**
   * Create an HttpClient with the given SSLContext.
   */
  private static HttpClient createHttpClientWithSslContext(SSLContext sslContext)
  {
    SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);

    Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
        .register("https", sslSocketFactory)
        .build();

    PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(registry);

    return HttpClients.custom()
        .setSSLContext(sslContext)
        .setConnectionManager(connectionManager)
        .build();
  }
}
