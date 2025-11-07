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

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeAnnouncer;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.common.logger.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Announces Druid nodes to Consul and maintains their health status via TTL checks.
 */
public class ConsulDruidNodeAnnouncer implements DruidNodeAnnouncer
{
  private static final Logger LOGGER = new Logger(ConsulDruidNodeAnnouncer.class);

  private final ConsulApiClient consulApiClient;
  private final ConsulDiscoveryConfig config;
  private final Map<String, DiscoveryDruidNode> announcedNodes = new ConcurrentHashMap<>();
  private final ScheduledExecutorService healthCheckExecutor;

  @Inject
  public ConsulDruidNodeAnnouncer(
      ConsulApiClient consulApiClient,
      ConsulDiscoveryConfig config
  )
  {
    this.consulApiClient = Preconditions.checkNotNull(consulApiClient, "consulApiClient");
    this.config = Preconditions.checkNotNull(config, "config");
    this.healthCheckExecutor = Execs.scheduledSingleThreaded("ConsulHealthCheck-%d");
  }

  @LifecycleStart
  public void start()
  {
    LOGGER.info("Starting ConsulDruidNodeAnnouncer");

    // Schedule periodic health check updates
    long intervalMs = config.getHealthCheckInterval().getMillis();
    healthCheckExecutor.scheduleAtFixedRate(
        this::updateHealthChecks,
        intervalMs,
        intervalMs,
        TimeUnit.MILLISECONDS
    );
  }

  @LifecycleStop
  public void stop()
  {
    LOGGER.info("Stopping ConsulDruidNodeAnnouncer");

    healthCheckExecutor.shutdownNow();

    // Deregister all announced nodes
    for (String serviceId : announcedNodes.keySet()) {
      try {
        consulApiClient.deregisterService(serviceId);
      }
      catch (Exception e) {
        LOGGER.error(e, "Failed to deregister service [%s] during shutdown", serviceId);
      }
    }

    announcedNodes.clear();
  }

  @Override
  public void announce(DiscoveryDruidNode discoveryDruidNode)
  {
    LOGGER.info("Announcing DiscoveryDruidNode[%s]", discoveryDruidNode);

    try {
      String serviceId = makeServiceId(discoveryDruidNode);
      consulApiClient.registerService(discoveryDruidNode);
      announcedNodes.put(serviceId, discoveryDruidNode);

      LOGGER.info("Successfully announced DiscoveryDruidNode[%s]", discoveryDruidNode);
    }
    catch (Exception e) {
      throw new RuntimeException(
          "Failed to announce DiscoveryDruidNode[" + discoveryDruidNode + "]",
          e
      );
    }
  }

  @Override
  public void unannounce(DiscoveryDruidNode discoveryDruidNode)
  {
    LOGGER.info("Unannouncing DiscoveryDruidNode[%s]", discoveryDruidNode);

    try {
      String serviceId = makeServiceId(discoveryDruidNode);
      consulApiClient.deregisterService(serviceId);
      announcedNodes.remove(serviceId);

      LOGGER.info("Successfully unannounced DiscoveryDruidNode[%s]", discoveryDruidNode);
    }
    catch (Exception e) {
      // Unannouncement happens during shutdown, don't throw
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      LOGGER.error(e, "Failed to unannounce DiscoveryDruidNode[%s]", discoveryDruidNode);
    }
  }

  private void updateHealthChecks()
  {
    for (Map.Entry<String, DiscoveryDruidNode> entry : announcedNodes.entrySet()) {
      String serviceId = entry.getKey();
      try {
        // Update TTL check to keep service healthy
        com.ecwid.consul.v1.ConsulClient client = getConsulClient();
        client.agentCheckPass("service:" + serviceId, "Druid node is healthy");
      }
      catch (Exception e) {
        LOGGER.error(e, "Failed to update health check for service [%s]", serviceId);
      }
    }
  }

  private String makeServiceId(DiscoveryDruidNode node)
  {
    String serviceName = config.getServicePrefix() + "-" + node.getNodeRole().getJsonName();
    return serviceName + "-" +
           node.getDruidNode().getHost() + "-" +
           node.getDruidNode().getPlaintextPort();
  }

  // Helper method to get the underlying Consul client for health checks
  private com.ecwid.consul.v1.ConsulClient getConsulClient()
  {
    // Access the consul client from DefaultConsulApiClient
    // This is a bit of a hack, but necessary for TTL health updates
    if (consulApiClient instanceof DefaultConsulApiClient) {
      return ((DefaultConsulApiClient) consulApiClient).getConsulClient();
    }
    throw new IllegalStateException("ConsulApiClient is not DefaultConsulApiClient");
  }
}
