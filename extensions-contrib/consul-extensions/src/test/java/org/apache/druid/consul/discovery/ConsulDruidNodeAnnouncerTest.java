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

import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.server.DruidNode;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ConsulDruidNodeAnnouncerTest
{
  private final DiscoveryDruidNode testNode = new DiscoveryDruidNode(
      new DruidNode("druid/broker", "test-host", true, 8082, null, true, false),
      NodeRole.BROKER,
      null
  );

  private final ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
      "localhost",
      8500,
      "druid",
      null,
      null,
      null,
      null,
      null,
      null,
      null,
      Duration.millis(10000),
      Duration.millis(90000),
      Duration.millis(60000),
      null,
      Duration.millis(10000)
  );

  private ConsulApiClient mockConsulApiClient;
  private ConsulDruidNodeAnnouncer announcer;

  @Before
  public void setUp()
  {
    mockConsulApiClient = EasyMock.createMock(ConsulApiClient.class);
    announcer = new ConsulDruidNodeAnnouncer(mockConsulApiClient, config);
  }

  @After
  public void tearDown()
  {
    if (announcer != null) {
      announcer.stop();
    }
  }

  @Test
  public void testAnnounce() throws Exception
  {
    Capture<DiscoveryDruidNode> nodeCapture = Capture.newInstance();
    mockConsulApiClient.registerService(EasyMock.capture(nodeCapture));
    EasyMock.expectLastCall().once();

    // Expect deregisterService to be called during cleanup
    mockConsulApiClient.deregisterService(EasyMock.anyString());
    EasyMock.expectLastCall().once();

    EasyMock.replay(mockConsulApiClient);

    announcer.announce(testNode);
    Assert.assertEquals(testNode, nodeCapture.getValue());

    // Manually stop announcer to trigger cleanup before verify
    announcer.stop();
    announcer = null;

    EasyMock.verify(mockConsulApiClient);
  }

  @Test
  public void testUnannounce() throws Exception
  {
    Capture<DiscoveryDruidNode> registerCapture = Capture.newInstance();
    mockConsulApiClient.registerService(EasyMock.capture(registerCapture));
    EasyMock.expectLastCall().once();

    Capture<String> deregisterCapture = Capture.newInstance();
    mockConsulApiClient.deregisterService(EasyMock.capture(deregisterCapture));
    EasyMock.expectLastCall().once();

    EasyMock.replay(mockConsulApiClient);

    announcer.announce(testNode);
    announcer.unannounce(testNode);

    EasyMock.verify(mockConsulApiClient);

    // Verify service ID format
    String expectedServiceId = "druid-broker-test-host-8082";
    Assert.assertEquals(expectedServiceId, deregisterCapture.getValue());
  }

  @Test
  public void testAnnounceFails() throws Exception
  {
    mockConsulApiClient.registerService(EasyMock.anyObject(DiscoveryDruidNode.class));
    EasyMock.expectLastCall().andThrow(new RuntimeException("Consul unavailable"));

    EasyMock.replay(mockConsulApiClient);

    try {
      announcer.announce(testNode);
      Assert.fail("Expected RuntimeException");
    }
    catch (RuntimeException e) {
      Assert.assertTrue(e.getMessage().contains("Failed to announce"));
    }

    EasyMock.verify(mockConsulApiClient);
  }
}
