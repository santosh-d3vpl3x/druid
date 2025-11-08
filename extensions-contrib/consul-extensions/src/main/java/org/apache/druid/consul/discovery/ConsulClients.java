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
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.HttpClient;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

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
    String basicUser = config.getBasicAuthUser();
    String basicPass = config.getBasicAuthPassword();

    // If TLS is configured, use HTTPS
    if (sslConfig != null && sslConfig.getTrustStorePath() != null) {
      try {
        // Build SSLContext using Druid's TLSUtils
        SSLContext sslContext = buildSslContext(sslConfig);
        
        // Create custom HttpClient with SSL support and optional Basic Auth
        HttpClient httpClient = createHttpClientWithOptionalBasicAuth(sslContext, basicUser, basicPass);

        // Use "https://" prefix to signal HTTPS to Consul client
        String httpsHost = "https://" + config.getHost();

        // Use constructor that accepts custom HttpClient
        ConsulRawClient rawClient = new ConsulRawClient(httpsHost, config.getPort(), httpClient);
        LOGGER.info("Created Consul client with HTTPS to %s:%d", config.getHost(), config.getPort());
        return new ConsulClient(rawClient);
      }
      catch (Exception e) {
        // TLS was explicitly configured; fail fast rather than silently downgrade to HTTP
        LOGGER.error(e, "Failed to configure TLS for Consul client (host: %s, port: %d)", config.getHost(), config.getPort());
        throw new IllegalStateException("Consul TLS configuration failed; refusing to fall back to HTTP");
      }
    }

    // No TLS or TLS failed - use plain HTTP, still honor Basic Auth if configured
    HttpClient httpClient = createHttpClientWithOptionalBasicAuth(null, basicUser, basicPass);
    String httpHost = "http://" + config.getHost();
    ConsulRawClient rawClient = new ConsulRawClient(httpHost, config.getPort(), httpClient);
    if (basicUser != null && basicPass != null) {
      LOGGER.warn("Using Basic Auth to Consul over plain HTTP (host: %s, port: %d). Credentials may be exposed.", config.getHost(), config.getPort());
    }
    LOGGER.info("Created Consul client with HTTP to %s:%d", config.getHost(), config.getPort());
    return new ConsulClient(rawClient);
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
  private static HttpClient createHttpClientWithOptionalBasicAuth(SSLContext sslContext,
                                                                 String basicUser,
                                                                 String basicPass)
  {
    HttpClientBuilder httpBuilder = HttpClients.custom();

    if (sslContext != null) {
      SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);
      Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
          .register("https", sslSocketFactory)
          .build();
      PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(registry);
      httpBuilder = httpBuilder.setSSLContext(sslContext).setConnectionManager(connectionManager);
    }

    if (basicUser != null && basicPass != null) {
      final String token = Base64.getEncoder().encodeToString((basicUser + ":" + basicPass).getBytes(StandardCharsets.UTF_8));
      HttpRequestInterceptor authInjector = (request, context) -> {
        if (!request.containsHeader("Authorization")) {
          request.addHeader("Authorization", "Basic " + token);
        }
      };
      httpBuilder = httpBuilder.addInterceptorFirst(authInjector);
    }

    return httpBuilder.build();
  }
}
