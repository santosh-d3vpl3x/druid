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
import org.apache.druid.https.SSLClientConfig;
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
  @Nullable
  private final SSLClientConfig sslClientConfig;

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

  @JsonProperty
  private final long leaderMaxErrorRetries;

  @JsonProperty
  private final Duration leaderRetryBackoffMax;

  @JsonProperty
  @Nullable
  private final java.util.Map<String, String> serviceTags;

  @JsonCreator
  public ConsulDiscoveryConfig(
      @JsonProperty("host") String host,
      @JsonProperty("port") Integer port,
      @JsonProperty("servicePrefix") String servicePrefix,
      @JsonProperty("aclToken") String aclToken,
      @JsonProperty("datacenter") String datacenter,
      @JsonProperty("coordinatorLeaderLockPath") String coordinatorLeaderLockPath,
      @JsonProperty("overlordLeaderLockPath") String overlordLeaderLockPath,
      @JsonProperty("sslClientConfig") SSLClientConfig sslClientConfig,
      @JsonProperty("basicAuthUser") String basicAuthUser,
      @JsonProperty("basicAuthPassword") String basicAuthPassword,
      @JsonProperty("healthCheckInterval") Duration healthCheckInterval,
      @JsonProperty("deregisterAfter") Duration deregisterAfter,
      @JsonProperty("watchSeconds") Duration watchSeconds,
      @JsonProperty("maxWatchRetries") Long maxWatchRetries,
      @JsonProperty("watchRetryDelay") Duration watchRetryDelay,
      @JsonProperty("leaderMaxErrorRetries") Long leaderMaxErrorRetries,
      @JsonProperty("leaderRetryBackoffMax") Duration leaderRetryBackoffMax,
      @JsonProperty("serviceTags") java.util.Map<String, String> serviceTags
  )
  {
    this.host = host == null ? "localhost" : host;
    int portValue = port == null ? 8500 : port;
    if (portValue < 1 || portValue > 65535) {
      throw new IllegalArgumentException("Port must be between 1 and 65535");
    }
    this.port = portValue;

    if (servicePrefix == null || servicePrefix.isEmpty()) {
      throw new IllegalArgumentException("servicePrefix cannot be null/empty");
    }
    this.servicePrefix = servicePrefix;

    this.aclToken = aclToken;
    this.datacenter = datacenter;
    this.coordinatorLeaderLockPath = coordinatorLeaderLockPath != null
        ? coordinatorLeaderLockPath
        : "druid/leader/coordinator";
    this.overlordLeaderLockPath = overlordLeaderLockPath != null
        ? overlordLeaderLockPath
        : "druid/leader/overlord";
    this.sslClientConfig = sslClientConfig;
    this.basicAuthUser = basicAuthUser;
    this.basicAuthPassword = basicAuthPassword;

    // Validate durations are non-negative
    Duration healthCheckIntervalValue = healthCheckInterval == null ? Duration.millis(10000) : healthCheckInterval;
    if (healthCheckIntervalValue.getMillis() < 0) {
      throw new IllegalArgumentException("healthCheckInterval cannot be negative");
    }
    this.healthCheckInterval = healthCheckIntervalValue;

    Duration deregisterAfterValue = deregisterAfter == null ? Duration.millis(90000) : deregisterAfter;
    if (deregisterAfterValue.getMillis() < 0) {
      throw new IllegalArgumentException("deregisterAfter cannot be negative");
    }
    this.deregisterAfter = deregisterAfterValue;

    Duration watchSecondsValue = watchSeconds == null ? Duration.millis(60000) : watchSeconds;
    if (watchSecondsValue.getMillis() < 0) {
      throw new IllegalArgumentException("watchSeconds cannot be negative");
    }
    this.watchSeconds = watchSecondsValue;
    // Treat null or non-positive values as unlimited
    if (maxWatchRetries == null || maxWatchRetries <= 0L) {
      this.maxWatchRetries = Long.MAX_VALUE;
    } else {
      this.maxWatchRetries = maxWatchRetries;
    }

    Duration watchRetryDelayValue = watchRetryDelay == null ? Duration.millis(10000) : watchRetryDelay;
    if (watchRetryDelayValue.getMillis() < 0) {
      throw new IllegalArgumentException("watchRetryDelay cannot be negative");
    }
    this.watchRetryDelay = watchRetryDelayValue;

    if (leaderMaxErrorRetries == null || leaderMaxErrorRetries <= 0L) {
      this.leaderMaxErrorRetries = Long.MAX_VALUE;
    } else {
      this.leaderMaxErrorRetries = leaderMaxErrorRetries;
    }

    Duration leaderRetryBackoffMaxValue = leaderRetryBackoffMax == null ? Duration.millis(300_000) : leaderRetryBackoffMax;
    if (leaderRetryBackoffMaxValue.getMillis() <= 0) {
      throw new IllegalArgumentException("leaderRetryBackoffMax must be positive");
    }
    this.leaderRetryBackoffMax = leaderRetryBackoffMaxValue;

    this.serviceTags = serviceTags;
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
  @Nullable
  public SSLClientConfig getSslClientConfig()
  {
    return sslClientConfig;
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

  @JsonProperty
  public long getLeaderMaxErrorRetries()
  {
    return leaderMaxErrorRetries;
  }

  @JsonProperty
  public Duration getLeaderRetryBackoffMax()
  {
    return leaderRetryBackoffMax;
  }

  @JsonProperty
  @Nullable
  public java.util.Map<String, String> getServiceTags()
  {
    return serviceTags;
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
           host.equals(that.host) &&
           servicePrefix.equals(that.servicePrefix) &&
           Objects.equals(aclToken, that.aclToken) &&
           Objects.equals(datacenter, that.datacenter) &&
           Objects.equals(coordinatorLeaderLockPath, that.coordinatorLeaderLockPath) &&
           Objects.equals(overlordLeaderLockPath, that.overlordLeaderLockPath) &&
           Objects.equals(sslClientConfig, that.sslClientConfig) &&
           Objects.equals(basicAuthUser, that.basicAuthUser) &&
           Objects.equals(basicAuthPassword, that.basicAuthPassword) &&
           Objects.equals(healthCheckInterval, that.healthCheckInterval) &&
           Objects.equals(deregisterAfter, that.deregisterAfter) &&
           Objects.equals(watchSeconds, that.watchSeconds) &&
           Objects.equals(watchRetryDelay, that.watchRetryDelay) &&
           leaderMaxErrorRetries == that.leaderMaxErrorRetries &&
           Objects.equals(leaderRetryBackoffMax, that.leaderRetryBackoffMax) &&
           Objects.equals(serviceTags, that.serviceTags);
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
        sslClientConfig,
        basicAuthUser,
        basicAuthPassword,
        healthCheckInterval,
        deregisterAfter,
        watchSeconds,
        maxWatchRetries,
        watchRetryDelay,
        leaderMaxErrorRetries,
        leaderRetryBackoffMax,
        serviceTags
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
           ", basicAuthUser='" + (basicAuthUser != null ? "*****" : "null") + '\'' +
           ", healthCheckInterval=" + healthCheckInterval +
           ", deregisterAfter=" + deregisterAfter +
           ", watchSeconds=" + watchSeconds +
           ", maxWatchRetries=" + maxWatchRetries +
           ", watchRetryDelay=" + watchRetryDelay +
           ", leaderMaxErrorRetries=" + (leaderMaxErrorRetries == Long.MAX_VALUE ? "-1" : leaderMaxErrorRetries) +
           ", leaderRetryBackoffMax=" + leaderRetryBackoffMax +
           ", serviceTags=" + serviceTags +
           '}';
  }
}
