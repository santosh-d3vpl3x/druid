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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import org.apache.druid.concurrent.LifecycleLock;
import org.apache.druid.discovery.BaseNodeRoleWatcher;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeDiscovery;
import org.apache.druid.discovery.DruidNodeDiscoveryProvider;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.ManageLifecycle;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.server.DruidNode;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Consul-based implementation of DruidNodeDiscoveryProvider.
 * Watches Consul for service changes and notifies listeners.
 */
@ManageLifecycle
public class ConsulDruidNodeDiscoveryProvider extends DruidNodeDiscoveryProvider
{
  private static final Logger LOGGER = new Logger(ConsulDruidNodeDiscoveryProvider.class);

  private final ConsulApiClient consulApiClient;
  private final ConsulDiscoveryConfig config;

  @com.google.inject.Inject(optional = true)
  private org.apache.druid.java.util.emitter.service.ServiceEmitter emitter;

  private ScheduledExecutorService listenerExecutor;

  private final ConcurrentHashMap<NodeRole, NodeRoleWatcher> nodeRoleWatchers = new ConcurrentHashMap<>();

  private final LifecycleLock lifecycleLock = new LifecycleLock();

  @Inject
  public ConsulDruidNodeDiscoveryProvider(
      ConsulApiClient consulApiClient,
      ConsulDiscoveryConfig config
  )
  {
    this.consulApiClient = Preconditions.checkNotNull(consulApiClient, "consulApiClient");
    this.config = Preconditions.checkNotNull(config, "config");
  }

  @Override
  public BooleanSupplier getForNode(DruidNode node, NodeRole nodeRole)
  {
    return () -> {
      try {
        List<DiscoveryDruidNode> nodes = consulApiClient.getHealthyServices(nodeRole);
        return nodes.stream()
                    .anyMatch(n -> n.getDruidNode().getHostAndPortToUse().equals(node.getHostAndPortToUse()));
      }
      catch (Exception e) {
        LOGGER.error(e, "Error checking for node [%s] with role [%s]", node, nodeRole);
        return false;
      }
    };
  }

  @Override
  public DruidNodeDiscovery getForNodeRole(NodeRole nodeRole)
  {
    Preconditions.checkState(lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS));

    return nodeRoleWatchers.computeIfAbsent(
        nodeRole,
        role -> {
          LOGGER.info("Creating NodeRoleWatcher for role[%s].", role);
          NodeRoleWatcher watcher = new NodeRoleWatcher(
              listenerExecutor,
              role,
              consulApiClient,
              config,
              emitter
          );
          watcher.start();
          LOGGER.info("Created NodeRoleWatcher for role[%s].", role);
          return watcher;
        }
    );
  }

  @LifecycleStart
  public void start()
  {
    if (!lifecycleLock.canStart()) {
      throw new ISE("can't start.");
    }

    try {
      LOGGER.info("Starting ConsulDruidNodeDiscoveryProvider");

      // Single-threaded executor ensures listener calls are executed in order
      listenerExecutor = Execs.scheduledSingleThreaded("ConsulDruidNodeDiscoveryProvider-ListenerExecutor");

      LOGGER.info("Started ConsulDruidNodeDiscoveryProvider");

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
      throw new ISE("can't stop.");
    }

    LOGGER.info("Stopping ConsulDruidNodeDiscoveryProvider");

    // Stop all watchers and remove them from the map
    for (NodeRoleWatcher watcher : nodeRoleWatchers.values()) {
      watcher.stop();
    }
    nodeRoleWatchers.clear();

    // Wait for watcher threads to finish before shutting down listener executor
    try {
      listenerExecutor.shutdown();
      if (!listenerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        LOGGER.warn("Listener executor did not terminate in time");
        listenerExecutor.shutdownNow();
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.warn("Interrupted while waiting for listener executor termination");
      listenerExecutor.shutdownNow();
    }

    LOGGER.info("Stopped ConsulDruidNodeDiscoveryProvider");
    lifecycleLock.exitStopAndReset();
  }

  @VisibleForTesting
  static class NodeRoleWatcher implements DruidNodeDiscovery
  {
    private static final Logger LOGGER = new Logger(NodeRoleWatcher.class);

    private final ConsulApiClient consulApiClient;
    private final ConsulDiscoveryConfig config;
    private final org.apache.druid.java.util.emitter.service.ServiceEmitter emitter;

    private ExecutorService watchExecutor;

    private final LifecycleLock lifecycleLock = new LifecycleLock();

    private final NodeRole nodeRole;
    private final BaseNodeRoleWatcher baseNodeRoleWatcher;

    private final AtomicLong retryCount = new AtomicLong(0);

    NodeRoleWatcher(
        ScheduledExecutorService listenerExecutor,
        NodeRole nodeRole,
        ConsulApiClient consulApiClient,
        ConsulDiscoveryConfig config,
        org.apache.druid.java.util.emitter.service.ServiceEmitter emitter
    )
    {
      this.nodeRole = nodeRole;
      this.consulApiClient = consulApiClient;
      this.config = config;
      this.emitter = emitter;
      this.baseNodeRoleWatcher = BaseNodeRoleWatcher.create(listenerExecutor, nodeRole);
    }

    private void watch()
    {
      boolean cacheInitialized = false;
      long consulIndex = 0;

      if (!lifecycleLock.awaitStarted()) {
        LOGGER.error("Lifecycle not started, Exited Watch for role[%s].", nodeRole);
        return;
      }

      while (lifecycleLock.awaitStarted(1, TimeUnit.MILLISECONDS)) {
        try {
          // Initial list to populate the cache
          if (!cacheInitialized) {
            List<DiscoveryDruidNode> nodes = consulApiClient.getHealthyServices(nodeRole);
            Map<String, DiscoveryDruidNode> nodeMap = new HashMap<>();
            for (DiscoveryDruidNode node : nodes) {
              nodeMap.put(node.getDruidNode().getHostAndPortToUse(), node);
            }
            baseNodeRoleWatcher.resetNodes(nodeMap);
            baseNodeRoleWatcher.cacheInitialized();
            cacheInitialized = true;

            LOGGER.info("Cache initialized for role[%s] with [%d] nodes", nodeRole, nodes.size());
          }

          // Watch for changes using Consul's blocking queries
          long watchSeconds = config.getWatchSeconds().getStandardSeconds();
          ConsulApiClient.ConsulWatchResult watchResult = consulApiClient.watchServices(
              nodeRole,
              consulIndex,
              watchSeconds
          );

          long newIndex = watchResult.getConsulIndex();
          if (newIndex != consulIndex) {
            // Index changed, means there was an update
            consulIndex = newIndex;

            // Get current state
            List<DiscoveryDruidNode> newNodes = watchResult.getNodes();
            Map<String, DiscoveryDruidNode> newNodeMap = new HashMap<>();
            for (DiscoveryDruidNode node : newNodes) {
              newNodeMap.put(node.getDruidNode().getHostAndPortToUse(), node);
            }

            // Calculate diff and notify
            Collection<DiscoveryDruidNode> currentNodes = baseNodeRoleWatcher.getAllNodes();
            Map<String, DiscoveryDruidNode> currentNodeMap = new HashMap<>();
            for (DiscoveryDruidNode node : currentNodes) {
              currentNodeMap.put(node.getDruidNode().getHostAndPortToUse(), node);
            }

            // Find added nodes
            for (Map.Entry<String, DiscoveryDruidNode> entry : newNodeMap.entrySet()) {
              if (!currentNodeMap.containsKey(entry.getKey())) {
                baseNodeRoleWatcher.childAdded(entry.getValue());
                ConsulMetrics.emitCount(emitter, "consul/watch/added", 1, "role", nodeRole.getJsonName());
              }
            }

            // Find removed nodes
            for (Map.Entry<String, DiscoveryDruidNode> entry : currentNodeMap.entrySet()) {
              if (!newNodeMap.containsKey(entry.getKey())) {
                baseNodeRoleWatcher.childRemoved(entry.getValue());
                ConsulMetrics.emitCount(emitter, "consul/watch/removed", 1, "role", nodeRole.getJsonName());
              }
            }
          }

          // Reset retry count on success
          retryCount.set(0);
        }
        catch (Throwable ex) {
          LOGGER.error(ex, "Exception while watching for role[%s].", nodeRole);

          ConsulMetrics.emitCount(emitter, "consul/watch/error", 1, "role", nodeRole.getJsonName());

          long count = retryCount.incrementAndGet();
          if (config.getMaxWatchRetries() != Long.MAX_VALUE && count > config.getMaxWatchRetries()) {
            LOGGER.error(
                "Max watch retries [%d] exceeded for role[%s]; giving up and stopping watcher.",
                config.getMaxWatchRetries(),
                nodeRole
            );
            ConsulMetrics.emitCount(emitter, "consul/watch/giveup", 1, "role", nodeRole.getJsonName());
            break; // exit watch loop and stop
          }

          // Exponential backoff with jitter, capped at 5 minutes
          long base = Math.max(1L, config.getWatchRetryDelay().getMillis());
          int exp = (int) Math.min(6, count); // cap exponential growth
          long backoff = Math.min(300_000L, base * (1L << exp));
          long jitter = (long) (backoff * (0.5 + java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
          sleep(jitter);
        }
      }

      LOGGER.info("Exited Watch for role[%s].", nodeRole);
    }

    private void sleep(long ms)
    {
      try {
        Thread.sleep(ms);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    public void start()
    {
      if (!lifecycleLock.canStart()) {
        throw new ISE("can't start.");
      }

      try {
        LOGGER.info("Starting NodeRoleWatcher for role[%s]...", nodeRole);
        this.watchExecutor = Execs.singleThreaded(this.getClass().getName() + nodeRole.getJsonName());
        watchExecutor.submit(this::watch);
        lifecycleLock.started();
        ConsulMetrics.emitCount(
            emitter,
            "consul/watch/lifecycle",
            1,
            "role",
            nodeRole.getJsonName(),
            "state",
            "start"
        );
        LOGGER.info("Started NodeRoleWatcher for role[%s].", nodeRole);
      }
      finally {
        lifecycleLock.exitStart();
      }
    }

    public void stop()
    {
      if (!lifecycleLock.canStop()) {
        throw new ISE("can't stop.");
      }

      try {
        LOGGER.info("Stopping NodeRoleWatcher for role[%s]...", nodeRole);
        watchExecutor.shutdownNow();

        if (!watchExecutor.awaitTermination(15, TimeUnit.SECONDS)) {
          LOGGER.warn("Failed to stop watchExecutor for role[%s]", nodeRole);
        }
        ConsulMetrics.emitCount(
            emitter,
            "consul/watch/lifecycle",
            1,
            "role",
            nodeRole.getJsonName(),
            "state",
            "stop"
        );
        LOGGER.info("Stopped NodeRoleWatcher for role[%s].", nodeRole);
      }
      catch (Exception ex) {
        LOGGER.error(ex, "Failed to stop NodeRoleWatcher for role[%s].", nodeRole);
      }
      finally {
        // Allow restart and leave lock in a clean state
        lifecycleLock.exitStopAndReset();
      }
    }

    @Override
    public Collection<DiscoveryDruidNode> getAllNodes()
    {
      return baseNodeRoleWatcher.getAllNodes();
    }

    @Override
    public void registerListener(Listener listener)
    {
      baseNodeRoleWatcher.registerListener(listener);
    }
  }
}
