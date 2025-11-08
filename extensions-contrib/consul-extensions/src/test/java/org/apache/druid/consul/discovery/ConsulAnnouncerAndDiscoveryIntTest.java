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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeDiscovery;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.jackson.DefaultObjectMapper;
import org.apache.druid.server.DruidNode;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration test for Consul discovery.
 * Requires a running Consul instance at localhost:8500.
 * Run with: mvn test -Dtest=ConsulAnnouncerAndDiscoveryIntTest
 *
 * To run Consul locally:
 * docker run -d --name=consul -p 8500:8500 consul:latest agent -server -ui -node=server-1 -bootstrap-expect=1 -client=0.0.0.0
 */
@Ignore("Requires running Consul instance")
public class ConsulAnnouncerAndDiscoveryIntTest
{
  private static final ObjectMapper JSON_MAPPER = new DefaultObjectMapper();

  private ConsulDiscoveryConfig config;
  private ConsulApiClient consulApiClient;
  private ConsulDruidNodeAnnouncer announcer;
  private ConsulDruidNodeDiscoveryProvider discoveryProvider;

  @Before
  public void setUp()
  {
    config = new ConsulDiscoveryConfig(
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
        Duration.millis(5000),
        Duration.millis(30000),
        Duration.millis(10000),
        null,
        Duration.millis(5000),
        null
    );

    consulApiClient = new DefaultConsulApiClient(config, JSON_MAPPER);
    announcer = new ConsulDruidNodeAnnouncer(consulApiClient, config);
    discoveryProvider = new ConsulDruidNodeDiscoveryProvider(consulApiClient, config);

    announcer.start();
    discoveryProvider.start();
  }

  @After
  public void tearDown()
  {
    if (announcer != null) {
      announcer.stop();
    }
    if (discoveryProvider != null) {
      discoveryProvider.stop();
    }
    if (consulApiClient != null) {
      try {
        consulApiClient.close();
      }
      catch (Exception e) {
        // Ignore
      }
    }
  }

  @Test
  public void testAnnouncementAndDiscovery() throws Exception
  {
    DiscoveryDruidNode node = new DiscoveryDruidNode(
        new DruidNode("druid/broker", "localhost", true, 8082, null, true, false),
        NodeRole.BROKER,
        null
    );

    CountDownLatch initLatch = new CountDownLatch(1);
    CountDownLatch addedLatch = new CountDownLatch(1);
    CountDownLatch removedLatch = new CountDownLatch(1);

    DruidNodeDiscovery discovery = discoveryProvider.getForNodeRole(NodeRole.BROKER);

    discovery.registerListener(new DruidNodeDiscovery.Listener()
    {
      @Override
      public void nodesAdded(Collection<DiscoveryDruidNode> nodes)
      {
        if (nodes.stream().anyMatch(n -> n.getDruidNode().getHostAndPortToUse().equals("localhost:8082"))) {
          addedLatch.countDown();
        }
      }

      @Override
      public void nodesRemoved(Collection<DiscoveryDruidNode> nodes)
      {
        if (nodes.stream().anyMatch(n -> n.getDruidNode().getHostAndPortToUse().equals("localhost:8082"))) {
          removedLatch.countDown();
        }
      }

      @Override
      public void nodeViewInitialized()
      {
        initLatch.countDown();
      }
    });

    // Wait for initialization
    Assert.assertTrue("Discovery initialization timed out", initLatch.await(10, TimeUnit.SECONDS));

    // Announce the node
    announcer.announce(node);

    // Wait for discovery to detect the announcement
    Assert.assertTrue("Node addition not detected", addedLatch.await(15, TimeUnit.SECONDS));

    // Verify node is in the list
    Collection<DiscoveryDruidNode> nodes = discovery.getAllNodes();
    Assert.assertTrue(
        "Announced node not found in discovery",
        nodes.stream().anyMatch(n -> n.getDruidNode().getHostAndPortToUse().equals("localhost:8082"))
    );

    // Unannounce the node
    announcer.unannounce(node);

    // Wait for discovery to detect the removal
    Assert.assertTrue("Node removal not detected", removedLatch.await(15, TimeUnit.SECONDS));

    // Verify node is no longer in the list
    nodes = discovery.getAllNodes();
    Assert.assertFalse(
        "Unannounced node still in discovery",
        nodes.stream().anyMatch(n -> n.getDruidNode().getHostAndPortToUse().equals("localhost:8082"))
    );
  }
}
