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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidLeaderSelector;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.server.DruidNode;
import org.joda.time.Duration;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Integration test for Consul leader election.
 *
 * This is not a unit test, but very helpful when making changes to ensure things work with real Consul server.
 * It is ignored in the build but checked in the repository for running manually by devs.
 *
 * To run this test:
 * 1. Start Consul: docker run -d -p 8500:8500 consul:1.15
 * 2. Remove @Ignore annotation
 * 3. Run the test
 */
@Ignore("Needs running Consul server")
public class ConsulLeaderElectionIntTest
{
  private static final ObjectMapper JSON_MAPPER = new DefaultObjectMapper();

  private final DiscoveryDruidNode testNode1 = new DiscoveryDruidNode(
      new DruidNode("druid/coordinator", "test-host1", true, 8081, null, true, false),
      NodeRole.COORDINATOR,
      null
  );

  private final DiscoveryDruidNode testNode2 = new DiscoveryDruidNode(
      new DruidNode("druid/coordinator", "test-host2", true, 8081, null, true, false),
      NodeRole.COORDINATOR,
      null
  );

  private final ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
      "localhost",
      8500,
      "druid-test",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      Duration.millis(5000),  // Fast intervals for testing
      Duration.millis(30000),
      Duration.millis(10000),
      null,
      Duration.millis(5000)
  );

  private final String lockPath = "druid-test/leader/coordinator";

  private final ConsulClient consulClient = new ConsulClient("localhost", 8500);

  /**
   * Test that when a leader crashes (becomeLeader() throws exception),
   * the stopBeingLeader() callback is properly invoked and cleanup happens.
   *
   * This simulates the scenario where a Coordinator becomes leader but then
   * encounters a fatal error during initialization.
   */
  @Test(timeout = 60000L)
  public void test_becomeLeader_exception() throws Exception
  {
    ConsulLeaderSelector leaderSelector = new ConsulLeaderSelector(
        testNode1.getDruidNode(),
        lockPath,
        config,
        consulClient
    );

    CountDownLatch becomeLeaderLatch = new CountDownLatch(1);
    CountDownLatch stopBeingLeaderLatch = new CountDownLatch(1);
    AtomicBoolean failed = new AtomicBoolean(false);

    leaderSelector.registerListener(new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becomeLeaderLatch.countDown();
        // This simulates a leader crash during initialization
        // In production, this would trigger System.exit() and pod restart
        throw new RuntimeException("Leader crashed during initialization");
      }

      @Override
      public void stopBeingLeader()
      {
        try {
          // Wait for becomeLeader to have been called
          becomeLeaderLatch.await();
          stopBeingLeaderLatch.countDown();
        }
        catch (InterruptedException ex) {
          failed.set(true);
        }
      }
    });

    // Wait for becomeLeader to be called (and throw exception)
    Assert.assertTrue("becomeLeader not called", becomeLeaderLatch.await(30, TimeUnit.SECONDS));

    // Wait for stopBeingLeader to be called (cleanup after exception)
    Assert.assertTrue("stopBeingLeader not called", stopBeingLeaderLatch.await(30, TimeUnit.SECONDS));

    // Verify no interruption errors occurred
    Assert.assertFalse("Test failed due to InterruptedException", failed.get());

    // Verify leader is no longer leader
    Assert.assertFalse("Should not be leader after exception", leaderSelector.isLeader());

    // Cleanup
    leaderSelector.unregisterListener();
  }

  /**
   * Test graceful leadership transfer when a leader candidate stops.
   *
   * Scenario:
   * 1. Node1 becomes leader
   * 2. Node1 gracefully unregisters (e.g., during shutdown)
   * 3. Node2 should automatically become the new leader
   *
   * This tests the automatic failover mechanism and verifies that
   * sessions are properly cleaned up when nodes stop.
   */
  @Test(timeout = 60000L)
  public void test_leaderCandidate_stopped() throws Exception
  {
    // First leader
    ConsulLeaderSelector leaderSelector1 = new ConsulLeaderSelector(
        testNode1.getDruidNode(),
        lockPath,
        config,
        consulClient
    );

    CountDownLatch becomeLeaderLatch1 = new CountDownLatch(1);
    CountDownLatch stopBeingLeaderLatch1 = new CountDownLatch(1);
    AtomicBoolean failed = new AtomicBoolean(false);

    leaderSelector1.registerListener(new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becomeLeaderLatch1.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        try {
          becomeLeaderLatch1.await();
          stopBeingLeaderLatch1.countDown();
        }
        catch (InterruptedException ex) {
          failed.set(true);
        }
      }
    });

    // Wait for node1 to become leader
    Assert.assertTrue("Node1 did not become leader", becomeLeaderLatch1.await(30, TimeUnit.SECONDS));
    Assert.assertTrue("Node1 should be leader", leaderSelector1.isLeader());
    Assert.assertEquals("Node1 should have term 1", 1, leaderSelector1.localTerm());

    // Node1 gracefully stops (e.g., during shutdown)
    leaderSelector1.unregisterListener();

    // Wait for node1 to stop being leader
    Assert.assertTrue("Node1 did not stop being leader", stopBeingLeaderLatch1.await(30, TimeUnit.SECONDS));
    Assert.assertFalse("Test failed due to InterruptedException", failed.get());

    // Now start second leader candidate
    ConsulLeaderSelector leaderSelector2 = new ConsulLeaderSelector(
        testNode2.getDruidNode(),
        lockPath,
        config,
        consulClient
    );

    CountDownLatch becomeLeaderLatch2 = new CountDownLatch(1);

    leaderSelector2.registerListener(new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becomeLeaderLatch2.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        // Not expected in this test
      }
    });

    // Wait for node2 to become leader
    Assert.assertTrue("Node2 did not become leader", becomeLeaderLatch2.await(30, TimeUnit.SECONDS));
    Assert.assertTrue("Node2 should be leader", leaderSelector2.isLeader());
    Assert.assertEquals("Node2 should have term 1", 1, leaderSelector2.localTerm());

    // Verify current leader is node2
    String currentLeader = leaderSelector2.getCurrentLeader();
    Assert.assertTrue(
        "Current leader should be node2",
        currentLeader != null && currentLeader.contains("test-host2")
    );

    // Cleanup
    leaderSelector2.unregisterListener();
  }

  /**
   * Test that multiple candidates competing for leadership works correctly.
   *
   * Scenario:
   * 1. Start two candidates simultaneously
   * 2. One should become leader, the other should wait
   * 3. When leader stops, the waiting candidate should take over
   *
   * This tests the lock contention and queuing behavior.
   */
  @Test(timeout = 60000L)
  public void test_multipleCandidates_leaderElection() throws Exception
  {
    ConsulLeaderSelector leaderSelector1 = new ConsulLeaderSelector(
        testNode1.getDruidNode(),
        lockPath,
        config,
        consulClient
    );

    ConsulLeaderSelector leaderSelector2 = new ConsulLeaderSelector(
        testNode2.getDruidNode(),
        lockPath,
        config,
        consulClient
    );

    CountDownLatch becomeLeaderLatch1 = new CountDownLatch(1);
    CountDownLatch becomeLeaderLatch2 = new CountDownLatch(1);
    CountDownLatch stopBeingLeaderLatch1 = new CountDownLatch(1);

    leaderSelector1.registerListener(new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becomeLeaderLatch1.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        stopBeingLeaderLatch1.countDown();
      }
    });

    leaderSelector2.registerListener(new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becomeLeaderLatch2.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        // Not expected in this test
      }
    });

    // One of them should become leader quickly
    boolean node1BecameLeader = becomeLeaderLatch1.await(30, TimeUnit.SECONDS);
    boolean node2BecameLeader = becomeLeaderLatch2.await(1, TimeUnit.SECONDS);

    // Exactly one should be leader
    Assert.assertTrue(
        "One node should have become leader",
        (node1BecameLeader && !node2BecameLeader) || (!node1BecameLeader && node2BecameLeader)
    );

    ConsulLeaderSelector currentLeader = node1BecameLeader ? leaderSelector1 : leaderSelector2;
    ConsulLeaderSelector waitingCandidate = node1BecameLeader ? leaderSelector2 : leaderSelector1;
    CountDownLatch waitingLatch = node1BecameLeader ? becomeLeaderLatch2 : becomeLeaderLatch1;

    Assert.assertTrue("Current leader should be leader", currentLeader.isLeader());
    Assert.assertFalse("Waiting candidate should not be leader", waitingCandidate.isLeader());

    // Stop current leader
    currentLeader.unregisterListener();
    stopBeingLeaderLatch1.await(10, TimeUnit.SECONDS);

    // Waiting candidate should become leader
    Assert.assertTrue(
        "Waiting candidate should become leader",
        waitingLatch.await(30, TimeUnit.SECONDS)
    );
    Assert.assertTrue("Waiting candidate should now be leader", waitingCandidate.isLeader());

    // Cleanup
    waitingCandidate.unregisterListener();
  }
}
