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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import org.joda.time.Duration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Configuration for Consul-based service discovery.
 */
public class ConsulDiscoveryConfig
{
  @JsonProperty
  @Nonnull
  private final String host;

  @JsonProperty
  private final int port;

  @JsonProperty
  @Nonnull
  private final String servicePrefix;

  @JsonProperty
  @Nullable
  private final String aclToken;

  @JsonProperty
  @Nullable
  private final String datacenter;

  @JsonProperty
  @Nonnull
  private final String coordinatorLeaderLockPath;

  @JsonProperty
  @Nonnull
  private final String overlordLeaderLockPath;

  @JsonProperty
  private final boolean enableTls;

  @JsonProperty
  @Nullable
  private final String tlsCertificatePath;

  @JsonProperty
  @Nullable
  private final String tlsKeyPath;

  @JsonProperty
  @Nullable
  private final String tlsCaCertPath;

  @JsonProperty
  private final boolean tlsVerifyHostname;

  @JsonProperty
  @Nullable
  private final String basicAuthUser;

  @JsonProperty
  @Nullable
  private final String basicAuthPassword;

  @JsonProperty
  private final Duration healthCheckInterval;

  @JsonProperty
  private final Duration deregisterAfter;

  @JsonProperty
  private final Duration watchSeconds;

  @JsonProperty
  private final long maxWatchRetries;

  @JsonProperty
  private final Duration watchRetryDelay;

  @JsonCreator
  public ConsulDiscoveryConfig(
      @JsonProperty("host") String host,
      @JsonProperty("port") Integer port,
      @JsonProperty("servicePrefix") String servicePrefix,
      @JsonProperty("aclToken") String aclToken,
      @JsonProperty("datacenter") String datacenter,
      @JsonProperty("coordinatorLeaderLockPath") String coordinatorLeaderLockPath,
      @JsonProperty("overlordLeaderLockPath") String overlordLeaderLockPath,
      @JsonProperty("enableTls") Boolean enableTls,
      @JsonProperty("tlsCertificatePath") String tlsCertificatePath,
      @JsonProperty("tlsKeyPath") String tlsKeyPath,
      @JsonProperty("tlsCaCertPath") String tlsCaCertPath,
      @JsonProperty("tlsVerifyHostname") Boolean tlsVerifyHostname,
      @JsonProperty("basicAuthUser") String basicAuthUser,
      @JsonProperty("basicAuthPassword") String basicAuthPassword,
      @JsonProperty("healthCheckInterval") Duration healthCheckInterval,
      @JsonProperty("deregisterAfter") Duration deregisterAfter,
      @JsonProperty("watchSeconds") Duration watchSeconds,
      @JsonProperty("maxWatchRetries") Long maxWatchRetries,
      @JsonProperty("watchRetryDelay") Duration watchRetryDelay
  )
  {
    this.host = host == null ? "localhost" : host;
    this.port = port == null ? 8500 : port;

    Preconditions.checkArgument(
        servicePrefix != null && !servicePrefix.isEmpty(),
        "servicePrefix cannot be null or empty"
    );
    this.servicePrefix = servicePrefix;

    this.aclToken = aclToken;
    this.datacenter = datacenter;
    this.coordinatorLeaderLockPath = coordinatorLeaderLockPath != null
        ? coordinatorLeaderLockPath
        : "druid/leader/coordinator";
    this.overlordLeaderLockPath = overlordLeaderLockPath != null
        ? overlordLeaderLockPath
        : "druid/leader/overlord";
    this.enableTls = enableTls != null && enableTls;
    this.tlsCertificatePath = tlsCertificatePath;
    this.tlsKeyPath = tlsKeyPath;
    this.tlsCaCertPath = tlsCaCertPath;
    this.tlsVerifyHostname = tlsVerifyHostname == null || tlsVerifyHostname;
    this.basicAuthUser = basicAuthUser;
    this.basicAuthPassword = basicAuthPassword;
    this.healthCheckInterval = healthCheckInterval == null ? Duration.millis(10000) : healthCheckInterval;
    this.deregisterAfter = deregisterAfter == null ? Duration.millis(90000) : deregisterAfter;
    this.watchSeconds = watchSeconds == null ? Duration.millis(60000) : watchSeconds;
    this.maxWatchRetries = maxWatchRetries == null ? Long.MAX_VALUE : maxWatchRetries;
    this.watchRetryDelay = watchRetryDelay == null ? Duration.millis(10000) : watchRetryDelay;
  }

  @JsonProperty
  public String getHost()
  {
    return host;
  }

  @JsonProperty
  public int getPort()
  {
    return port;
  }

  @JsonProperty
  public String getServicePrefix()
  {
    return servicePrefix;
  }

  @JsonProperty
  @Nullable
  public String getAclToken()
  {
    return aclToken;
  }

  @JsonProperty
  @Nullable
  public String getDatacenter()
  {
    return datacenter;
  }

  @JsonProperty
  public String getCoordinatorLeaderLockPath()
  {
    return coordinatorLeaderLockPath;
  }

  @JsonProperty
  public String getOverlordLeaderLockPath()
  {
    return overlordLeaderLockPath;
  }

  @JsonProperty
  public boolean isEnableTls()
  {
    return enableTls;
  }

  @JsonProperty
  @Nullable
  public String getTlsCertificatePath()
  {
    return tlsCertificatePath;
  }

  @JsonProperty
  @Nullable
  public String getTlsKeyPath()
  {
    return tlsKeyPath;
  }

  @JsonProperty
  @Nullable
  public String getTlsCaCertPath()
  {
    return tlsCaCertPath;
  }

  @JsonProperty
  public boolean isTlsVerifyHostname()
  {
    return tlsVerifyHostname;
  }

  @JsonProperty
  @Nullable
  public String getBasicAuthUser()
  {
    return basicAuthUser;
  }

  @JsonProperty
  @Nullable
  public String getBasicAuthPassword()
  {
    return basicAuthPassword;
  }

  @JsonProperty
  public Duration getHealthCheckInterval()
  {
    return healthCheckInterval;
  }

  @JsonProperty
  public Duration getDeregisterAfter()
  {
    return deregisterAfter;
  }

  @JsonProperty
  public Duration getWatchSeconds()
  {
    return watchSeconds;
  }

  @JsonProperty
  public long getMaxWatchRetries()
  {
    return maxWatchRetries;
  }

  @JsonProperty
  public Duration getWatchRetryDelay()
  {
    return watchRetryDelay;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ConsulDiscoveryConfig that = (ConsulDiscoveryConfig) o;
    return port == that.port &&
           maxWatchRetries == that.maxWatchRetries &&
           enableTls == that.enableTls &&
           tlsVerifyHostname == that.tlsVerifyHostname &&
           host.equals(that.host) &&
           servicePrefix.equals(that.servicePrefix) &&
           Objects.equals(aclToken, that.aclToken) &&
           Objects.equals(datacenter, that.datacenter) &&
           Objects.equals(coordinatorLeaderLockPath, that.coordinatorLeaderLockPath) &&
           Objects.equals(overlordLeaderLockPath, that.overlordLeaderLockPath) &&
           Objects.equals(tlsCertificatePath, that.tlsCertificatePath) &&
           Objects.equals(tlsKeyPath, that.tlsKeyPath) &&
           Objects.equals(tlsCaCertPath, that.tlsCaCertPath) &&
           Objects.equals(basicAuthUser, that.basicAuthUser) &&
           Objects.equals(basicAuthPassword, that.basicAuthPassword) &&
           Objects.equals(healthCheckInterval, that.healthCheckInterval) &&
           Objects.equals(deregisterAfter, that.deregisterAfter) &&
           Objects.equals(watchSeconds, that.watchSeconds) &&
           Objects.equals(watchRetryDelay, that.watchRetryDelay);
  }

  @Override
  public int hashCode()
  {
    return Objects.hash(
        host,
        port,
        servicePrefix,
        aclToken,
        datacenter,
        coordinatorLeaderLockPath,
        overlordLeaderLockPath,
        enableTls,
        tlsCertificatePath,
        tlsKeyPath,
        tlsCaCertPath,
        tlsVerifyHostname,
        basicAuthUser,
        basicAuthPassword,
        healthCheckInterval,
        deregisterAfter,
        watchSeconds,
        maxWatchRetries,
        watchRetryDelay
    );
  }

  @Override
  public String toString()
  {
    return "ConsulDiscoveryConfig{" +
           "host='" + host + '\'' +
           ", port=" + port +
           ", servicePrefix='" + servicePrefix + '\'' +
           ", datacenter='" + datacenter + '\'' +
           ", enableTls=" + enableTls +
           ", tlsVerifyHostname=" + tlsVerifyHostname +
           ", basicAuthUser='" + basicAuthUser + '\'' +
           ", healthCheckInterval=" + healthCheckInterval +
           ", deregisterAfter=" + deregisterAfter +
           ", watchSeconds=" + watchSeconds +
           ", maxWatchRetries=" + maxWatchRetries +
           ", watchRetryDelay=" + watchRetryDelay +
           '}';
  }
}
