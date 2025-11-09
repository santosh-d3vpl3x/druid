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
import org.apache.druid.concurrent.LifecycleLock;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeAnnouncer;
import org.apache.druid.guice.ManageLifecycle;
import org.apache.druid.java.util.common.ISE;
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
@ManageLifecycle
public class ConsulDruidNodeAnnouncer implements DruidNodeAnnouncer
{
  private static final Logger LOGGER = new Logger(ConsulDruidNodeAnnouncer.class);

  private final ConsulApiClient consulApiClient;
  private final ConsulDiscoveryConfig config;
  private final Map<String, DiscoveryDruidNode> announcedNodes = new ConcurrentHashMap<>();
  private final ScheduledExecutorService healthCheckExecutor;
  @com.google.inject.Inject(optional = true)
  private org.apache.druid.java.util.emitter.service.ServiceEmitter emitter;
  private final LifecycleLock lifecycleLock = new LifecycleLock();

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
    if (!lifecycleLock.canStart()) {
      throw new ISE("can't start");
    }

    try {
      LOGGER.info("Starting ConsulDruidNodeAnnouncer");

      // Schedule periodic health check updates
      long intervalMs = config.getHealthCheckInterval().getMillis();
      healthCheckExecutor.scheduleAtFixedRate(
          this::updateHealthChecks,
          0L,
          intervalMs,
          TimeUnit.MILLISECONDS
      );
      lifecycleLock.started();
    }
    finally {
      lifecycleLock.exitStart();
    }
  }

  @LifecycleStop
  public void stop()
  {
    if (!lifecycleLock.canStop()) {
      throw new ISE("can't stop");
    }

    LOGGER.info("Stopping ConsulDruidNodeAnnouncer");

    // Shut down health check executor
    healthCheckExecutor.shutdownNow();

    // Wait for health check thread to terminate
    try {
      if (!healthCheckExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        LOGGER.warn("Health check executor did not terminate in time");
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warn("Interrupted while waiting for health check termination");
    }

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
    lifecycleLock.exitStop();
  }

  @Override
  public void announce(DiscoveryDruidNode discoveryDruidNode)
  {
    if (!lifecycleLock.awaitStarted(1, TimeUnit.SECONDS)) {
      throw new ISE("Announcer not started");
    }

    LOGGER.info("Announcing DiscoveryDruidNode[%s]", discoveryDruidNode);

    try {
      String serviceId = makeServiceId(discoveryDruidNode);
      consulApiClient.registerService(discoveryDruidNode);
      announcedNodes.put(serviceId, discoveryDruidNode);

      LOGGER.info("Successfully announced DiscoveryDruidNode[%s]", discoveryDruidNode);
      ConsulMetrics.emitCount(emitter, "consul/announce/success", 1,
          "role", discoveryDruidNode.getNodeRole().getJsonName());
    }
    catch (Exception e) {
      ConsulMetrics.emitCount(emitter, "consul/announce/failure", 1,
          "role", discoveryDruidNode.getNodeRole().getJsonName());
      throw new RuntimeException(
          "Failed to announce DiscoveryDruidNode[" + discoveryDruidNode + "]",
          e
      );
    }
  }

  @Override
  public void unannounce(DiscoveryDruidNode discoveryDruidNode)
  {
    if (!lifecycleLock.awaitStarted(1, TimeUnit.SECONDS)) {
      throw new ISE("Announcer not started");
    }

    LOGGER.info("Unannouncing DiscoveryDruidNode[%s]", discoveryDruidNode);

    try {
      String serviceId = makeServiceId(discoveryDruidNode);
      consulApiClient.deregisterService(serviceId);
      announcedNodes.remove(serviceId);

      LOGGER.info("Successfully unannounced DiscoveryDruidNode[%s]", discoveryDruidNode);
      ConsulMetrics.emitCount(emitter, "consul/unannounce/success", 1,
          "role", discoveryDruidNode.getNodeRole().getJsonName());
    }
    catch (Exception e) {
      // Unannouncement happens during shutdown, don't throw
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }

      LOGGER.error(e, "Failed to unannounce DiscoveryDruidNode[%s]", discoveryDruidNode);
      ConsulMetrics.emitCount(emitter, "consul/unannounce/failure", 1,
          "role", discoveryDruidNode.getNodeRole().getJsonName());
    }
  }

  private void updateHealthChecks()
  {
    for (Map.Entry<String, DiscoveryDruidNode> entry : announcedNodes.entrySet()) {
      String serviceId = entry.getKey();
      try {
        // Update TTL check to keep service healthy
        consulApiClient.passTtlCheck(serviceId, "Druid node is healthy");
      }
      catch (Exception e) {
        LOGGER.error(e, "Failed to update health check for service [%s]", serviceId);
        ConsulMetrics.emitCount(emitter, "consul/healthcheck/failure", 1);
      }
    }
  }

  private String makeServiceId(DiscoveryDruidNode node)
  {
    String serviceName = config.getServicePrefix() + "-" + node.getNodeRole().getJsonName();
    return serviceName + "-" +
           node.getDruidNode().getHost() + "-" +
           node.getDruidNode().getPortToUse();
  }
}
