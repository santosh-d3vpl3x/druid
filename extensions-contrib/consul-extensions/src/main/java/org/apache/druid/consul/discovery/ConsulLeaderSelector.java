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
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.ecwid.consul.v1.kv.model.PutParams;
import com.ecwid.consul.v1.session.model.NewSession;
import com.ecwid.consul.v1.session.model.Session;
import com.google.common.base.Preconditions;
import org.apache.druid.concurrent.LifecycleLock;
import org.apache.druid.discovery.DruidLeaderSelector;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.server.DruidNode;

import javax.annotation.Nullable;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consul-based implementation of DruidLeaderSelector using Consul sessions and locks.
 *
 * Leader election algorithm:
 * 1. Create a Consul session with TTL
 * 2. Try to acquire a lock on a KV key using the session
 * 3. If acquired, become leader
 * 4. Periodically renew session to maintain leadership
 * 5. Watch the key for changes to detect leadership loss
 * 6. If session expires or lock is lost, step down as leader
 */
public class ConsulLeaderSelector implements DruidLeaderSelector
{
  private static final Logger LOGGER = new Logger(ConsulLeaderSelector.class);

  private final LifecycleLock lifecycleLock = new LifecycleLock();
  private final DruidNode self;
  private final String lockKey;
  private final ConsulDiscoveryConfig config;
  private final ConsulClient consulClient;

  private DruidLeaderSelector.Listener listener = null;
  private final AtomicBoolean leader = new AtomicBoolean(false);
  private final AtomicInteger term = new AtomicInteger(0);

  private ScheduledExecutorService executorService;
  private volatile String sessionId;
  private volatile boolean stopping = false;

  public ConsulLeaderSelector(
      DruidNode self,
      String lockKey,
      ConsulDiscoveryConfig config,
      ConsulClient consulClient
  )
  {
    this.self = Preconditions.checkNotNull(self, "self");
    this.lockKey = Preconditions.checkNotNull(lockKey, "lockKey");
    this.config = Preconditions.checkNotNull(config, "config");
    this.consulClient = Preconditions.checkNotNull(consulClient, "consulClient");
  }

  @Nullable
  @Override
  public String getCurrentLeader()
  {
    try {
      Response<GetValue> response = consulClient.getKVValue(
          lockKey,
          config.getAclToken(),
          buildQueryParams()
      );
      if (response != null && response.getValue() != null && response.getValue().getValue() != null) {
        return new String(Base64.getDecoder().decode(response.getValue().getValue()));
      }
      return null;
    }
    catch (Exception e) {
      LOGGER.error(e, "Failed to get current leader from Consul");
      return null;
    }
  }

  @Override
  public boolean isLeader()
  {
    return leader.get();
  }

  @Override
  public int localTerm()
  {
    return term.get();
  }

  @Override
  public void registerListener(Listener listener)
  {
    Preconditions.checkArgument(listener != null, "listener is null");

    if (!lifecycleLock.canStart()) {
      throw new ISE("can't start");
    }

    try {
      this.listener = listener;
      this.executorService = Execs.scheduledSingleThreaded("ConsulLeaderSelector-%d");

      // Start the leader election loop
      startLeaderElection();

      lifecycleLock.started();
    }
    catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    finally {
      lifecycleLock.exitStart();
    }
  }

  @Override
  public void unregisterListener()
  {
    if (!lifecycleLock.canStop()) {
      throw new ISE("can't stop");
    }

    LOGGER.info("Unregistering leader selector for [%s]", lockKey);
    stopping = true;

    // Release leadership if we have it
    if (leader.get()) {
      try {
        listener.stopBeingLeader();
      }
      catch (Exception e) {
        LOGGER.error(e, "Exception while stopping being leader");
      }
      leader.set(false);
    }

    // Destroy session (this will release the lock)
    if (sessionId != null) {
      try {
        consulClient.sessionDestroy(sessionId, buildQueryParams(), config.getAclToken());
      }
      catch (Exception e) {
        LOGGER.error(e, "Failed to destroy Consul session");
      }
      sessionId = null;
    }

    // Shutdown executor
    if (executorService != null) {
      executorService.shutdownNow();
      try {
        if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
          LOGGER.warn("Leader selector executor did not terminate in time");
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void startLeaderElection()
  {
    executorService.submit(this::leaderElectionLoop);
  }

  private void leaderElectionLoop()
  {
    LOGGER.info("Starting leader election loop for [%s]", lockKey);

    while (!stopping && !Thread.currentThread().isInterrupted()) {
      try {
        // Create or recreate session
        if (sessionId == null) {
          sessionId = createSession();
          LOGGER.info("Created Consul session [%s] for leader election", sessionId);
        }

        // Try to acquire lock
        boolean acquired = tryAcquireLock(sessionId);

        if (acquired && !leader.get()) {
          // We just became leader
          becomeLeader();
        } else if (!acquired && leader.get()) {
          // We lost leadership
          loseLeadership();
        }

        if (leader.get()) {
          // We are leader, renew session periodically
          //noinspection BusyWait
          Thread.sleep(config.getHealthCheckInterval().getMillis());
          renewSession(sessionId);
        } else {
          // We are not leader, wait before retrying
          //noinspection BusyWait
          Thread.sleep(config.getHealthCheckInterval().getMillis());
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      catch (Exception e) {
        LOGGER.error(e, "Error in leader election loop");

        // Reset state and retry
        if (leader.get()) {
          loseLeadership();
        }
        sessionId = null;

        try {
          //noinspection BusyWait
          Thread.sleep(config.getWatchRetryDelay().getMillis());
        }
        catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }

    LOGGER.info("Exiting leader election loop for [%s]", lockKey);
  }

  private String createSession()
  {
    NewSession newSession = new NewSession();
    newSession.setName(StringUtils.format("druid-leader-%s", self.getHostAndPortToUse()));

    // Session TTL - if not renewed within this time, session expires and lock is released
    long ttlSeconds = Math.max(15, config.getHealthCheckInterval().getStandardSeconds() * 3);
    newSession.setTtl(StringUtils.format("%ds", ttlSeconds));

    // Behavior when session is invalidated
    newSession.setBehavior(Session.Behavior.DELETE);

    // Lock delay - prevents rapid re-acquisition after session invalidation (in seconds)
    newSession.setLockDelay(5L);

    Response<String> response = consulClient.sessionCreate(
        newSession,
        buildQueryParams(),
        config.getAclToken()
    );
    return response.getValue();
  }

  private void renewSession(String sessionId)
  {
    try {
      Response<Session> response = consulClient.renewSession(
          sessionId,
          buildQueryParams(),
          config.getAclToken()
      );
      if (response == null || response.getValue() == null) {
        LOGGER.warn("Failed to renew session [%s], session may have expired", sessionId);
        // Session expired, will be recreated in next loop iteration
        this.sessionId = null;
        if (leader.get()) {
          loseLeadership();
        }
      }
    }
    catch (Exception e) {
      LOGGER.error(e, "Failed to renew Consul session [%s]", sessionId);
      this.sessionId = null;
    }
  }

  private boolean tryAcquireLock(String sessionId)
  {
    try {
      // Write our identity to the key with the session
      String leaderValue = self.getServiceScheme() + "://" + self.getHostAndPortToUse();

      PutParams putParams = new PutParams();
      putParams.setAcquireSession(sessionId);
      Response<Boolean> response = consulClient.setKVValue(
          lockKey,
          leaderValue,
          config.getAclToken(),
          putParams,
          buildQueryParams()
      );

      return response != null && Boolean.TRUE.equals(response.getValue());
    }
    catch (Exception e) {
      LOGGER.error(e, "Failed to acquire lock on key [%s]", lockKey);
      return false;
    }
  }

  private void becomeLeader()
  {
    LOGGER.info("Becoming leader for [%s]", lockKey);

    try {
      leader.set(true);
      term.incrementAndGet();
      listener.becomeLeader();
      LOGGER.info("Successfully became leader for [%s], term [%d]", lockKey, term.get());
    }
    catch (Throwable ex) {
      LOGGER.error(ex, "listener.becomeLeader() failed");
      leader.set(false);

      // Destroy session to release lock
      if (sessionId != null) {
        try {
          consulClient.sessionDestroy(sessionId, buildQueryParams(), config.getAclToken());
        }
        catch (Exception e) {
          LOGGER.error(e, "Failed to destroy session after becomeLeader failure");
        }
        sessionId = null;
      }

      // In production, you might want to exit here like K8s does
      // System.exit(1);
    }
  }

  private void loseLeadership()
  {
    LOGGER.info("Losing leadership for [%s]", lockKey);

    leader.set(false);

    try {
      listener.stopBeingLeader();
      LOGGER.info("Successfully stepped down as leader for [%s]", lockKey);
    }
    catch (Throwable ex) {
      LOGGER.error(ex, "listener.stopBeingLeader() failed");
    }
  }

  private QueryParams buildQueryParams()
  {
    if (config.getDatacenter() != null) {
      return new QueryParams(config.getDatacenter());
    }
    return QueryParams.DEFAULT;
  }
}
