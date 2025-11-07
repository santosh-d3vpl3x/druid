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
import org.apache.druid.discovery.DruidLeaderSelector;
import org.apache.druid.server.DruidNode;
import org.easymock.Capture;
import org.easymock.EasyMock;
import org.joda.time.Duration;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ConsulLeaderSelectorTest
{
  private static final String LOCK_KEY = "druid/leader/coordinator";
  private static final String SESSION_ID = "test-session-id";

  private DruidNode selfNode;
  private ConsulDiscoveryConfig config;
  private ConsulClient mockConsulClient;
  private ConsulLeaderSelector leaderSelector;

  @Before
  public void setUp()
  {
    selfNode = new DruidNode(
        "druid/coordinator",
        "test-host",
        true,
        8081,
        null,
        true,
        false
    );

    config = new ConsulDiscoveryConfig(
        "localhost",
        8500,
        "druid",
        null,
        null,
        null, null,
        null, null, null, null, null, null, null,
        Duration.millis(1000),  // Short intervals for testing
        Duration.millis(5000),
        Duration.millis(1000),
        null,
        Duration.millis(1000)
    );

    mockConsulClient = EasyMock.createMock(ConsulClient.class);
    leaderSelector = new ConsulLeaderSelector(selfNode, LOCK_KEY, config, mockConsulClient);
  }

  @After
  public void tearDown()
  {
    if (leaderSelector != null) {
      EasyMock.reset(mockConsulClient);
      try {
        leaderSelector.unregisterListener();
      }
      catch (Exception e) {
        // Ignore
      }
    }
  }

  @Test
  public void testGetCurrentLeader() throws Exception
  {
    String leaderValue = "https://leader-host:8081";
    GetValue getValue = new GetValue();
    getValue.setValue(Base64.getEncoder().encodeToString(leaderValue.getBytes()));

    Response<GetValue> response = new Response<>(getValue, 0L, true, 0L);

    EasyMock.expect(mockConsulClient.getKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.isNull(),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(response)
            .once();

    EasyMock.replay(mockConsulClient);

    String currentLeader = leaderSelector.getCurrentLeader();
    Assert.assertEquals(leaderValue, currentLeader);

    EasyMock.verify(mockConsulClient);
  }

  @Test
  public void testGetCurrentLeaderNoValue() throws Exception
  {
    Response<GetValue> response = new Response<>(null, 0L, true, 0L);

    EasyMock.expect(mockConsulClient.getKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.isNull(),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(response)
            .once();

    EasyMock.replay(mockConsulClient);

    String currentLeader = leaderSelector.getCurrentLeader();
    Assert.assertNull(currentLeader);

    EasyMock.verify(mockConsulClient);
  }

  @Test
  public void testIsLeaderInitiallyFalse()
  {
    Assert.assertFalse(leaderSelector.isLeader());
  }

  @Test
  public void testLocalTermInitiallyZero()
  {
    Assert.assertEquals(0, leaderSelector.localTerm());
  }

  @Test
  public void testBecomeLeader() throws Exception
  {
    CountDownLatch becameLeaderLatch = new CountDownLatch(1);
    CountDownLatch sessionCreatedLatch = new CountDownLatch(1);

    // Mock session creation
    Capture<NewSession> sessionCapture = Capture.newInstance();
    EasyMock.expect(mockConsulClient.sessionCreate(
        EasyMock.capture(sessionCapture),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andAnswer(() -> {
              sessionCreatedLatch.countDown();
              return new Response<>(SESSION_ID, 0L, true, 0L);
            })
            .once();

    // Mock successful lock acquisition
    Capture<PutParams> putParamsCapture = Capture.newInstance();
    EasyMock.expect(mockConsulClient.setKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.anyString(),
        EasyMock.isNull(),
        EasyMock.capture(putParamsCapture),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(new Response<>(true, 0L, true, 0L))
            .once();

    // Mock session renewal
    Session session = new Session();
    session.setId(SESSION_ID);
    EasyMock.expect(mockConsulClient.renewSession(
        EasyMock.eq(SESSION_ID),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(session, 0L, true, 0L))
            .anyTimes();

    EasyMock.replay(mockConsulClient);

    DruidLeaderSelector.Listener listener = new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becameLeaderLatch.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        // Not expected in this test
      }
    };

    leaderSelector.registerListener(listener);

    // Wait for session creation and leader election
    Assert.assertTrue("Session not created", sessionCreatedLatch.await(5, TimeUnit.SECONDS));
    Assert.assertTrue("Did not become leader", becameLeaderLatch.await(5, TimeUnit.SECONDS));

    // Verify we became leader
    Assert.assertTrue(leaderSelector.isLeader());
    Assert.assertEquals(1, leaderSelector.localTerm());

    // Verify session was created correctly
    NewSession createdSession = sessionCapture.getValue();
    Assert.assertNotNull(createdSession);
    Assert.assertEquals(Session.Behavior.DELETE, createdSession.getBehavior());
    Assert.assertEquals(5L, createdSession.getLockDelay());

    // Verify lock acquisition used the session
    PutParams putParams = putParamsCapture.getValue();
    Assert.assertEquals(SESSION_ID, putParams.getAcquireSession());

    EasyMock.verify(mockConsulClient);
  }

  @Test
  public void testLoseLeadership() throws Exception
  {
    CountDownLatch becameLeaderLatch = new CountDownLatch(1);
    CountDownLatch stoppedBeingLeaderLatch = new CountDownLatch(1);

    // Mock session creation
    EasyMock.expect(mockConsulClient.sessionCreate(
        EasyMock.anyObject(NewSession.class),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(SESSION_ID, 0L, true, 0L))
            .once();

    // Mock successful lock acquisition first
    EasyMock.expect(mockConsulClient.setKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.anyString(),
        EasyMock.isNull(),
        EasyMock.anyObject(PutParams.class),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(new Response<>(true, 0L, true, 0L))
            .once();

    // Mock session renewal
    Session session = new Session();
    session.setId(SESSION_ID);
    EasyMock.expect(mockConsulClient.renewSession(
        EasyMock.eq(SESSION_ID),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(session, 0L, true, 0L))
            .anyTimes();

    // Mock failed lock acquisition (lost leadership)
    EasyMock.expect(mockConsulClient.setKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.anyString(),
        EasyMock.isNull(),
        EasyMock.anyObject(PutParams.class),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(new Response<>(false, 0L, true, 0L))
            .anyTimes();

    EasyMock.replay(mockConsulClient);

    DruidLeaderSelector.Listener listener = new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becameLeaderLatch.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        stoppedBeingLeaderLatch.countDown();
      }
    };

    leaderSelector.registerListener(listener);

    // Wait for leadership
    Assert.assertTrue("Did not become leader", becameLeaderLatch.await(5, TimeUnit.SECONDS));
    Assert.assertTrue(leaderSelector.isLeader());

    // Wait for leadership loss
    Assert.assertTrue("Did not lose leadership", stoppedBeingLeaderLatch.await(5, TimeUnit.SECONDS));
    Assert.assertFalse(leaderSelector.isLeader());

    EasyMock.verify(mockConsulClient);
  }

  @Test
  public void testSessionRenewalFailure() throws Exception
  {
    CountDownLatch becameLeaderLatch = new CountDownLatch(1);
    CountDownLatch stoppedBeingLeaderLatch = new CountDownLatch(1);

    // Mock session creation
    EasyMock.expect(mockConsulClient.sessionCreate(
        EasyMock.anyObject(NewSession.class),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(SESSION_ID, 0L, true, 0L))
            .anyTimes();

    // Mock successful lock acquisition
    EasyMock.expect(mockConsulClient.setKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.anyString(),
        EasyMock.isNull(),
        EasyMock.anyObject(PutParams.class),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(new Response<>(true, 0L, true, 0L))
            .anyTimes();

    // Mock failed session renewal (session expired)
    EasyMock.expect(mockConsulClient.renewSession(
        EasyMock.eq(SESSION_ID),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<Session>(null, 0L, true, 0L))
            .anyTimes();

    EasyMock.replay(mockConsulClient);

    DruidLeaderSelector.Listener listener = new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        becameLeaderLatch.countDown();
      }

      @Override
      public void stopBeingLeader()
      {
        stoppedBeingLeaderLatch.countDown();
      }
    };

    leaderSelector.registerListener(listener);

    // Wait for leadership
    Assert.assertTrue("Did not become leader", becameLeaderLatch.await(5, TimeUnit.SECONDS));

    // Wait for leadership loss due to failed renewal
    Assert.assertTrue("Did not lose leadership", stoppedBeingLeaderLatch.await(5, TimeUnit.SECONDS));

    EasyMock.verify(mockConsulClient);
  }

  @Test
  public void testUnregisterDestroysSession() throws Exception
  {
    // Mock session creation
    EasyMock.expect(mockConsulClient.sessionCreate(
        EasyMock.anyObject(NewSession.class),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(SESSION_ID, 0L, true, 0L))
            .once();

    // Mock lock acquisition attempts
    EasyMock.expect(mockConsulClient.setKVValue(
        EasyMock.eq(LOCK_KEY),
        EasyMock.anyString(),
        EasyMock.isNull(),
        EasyMock.anyObject(PutParams.class),
        EasyMock.eq(QueryParams.DEFAULT)
    ))
            .andReturn(new Response<>(false, 0L, true, 0L))
            .anyTimes();

    // Mock session destruction
    Capture<String> sessionIdCapture = Capture.newInstance();
    EasyMock.expect(mockConsulClient.sessionDestroy(
        EasyMock.capture(sessionIdCapture),
        EasyMock.eq(QueryParams.DEFAULT),
        EasyMock.isNull()
    ))
            .andReturn(new Response<>(null, 0L, true, 0L))
            .once();

    EasyMock.replay(mockConsulClient);

    DruidLeaderSelector.Listener listener = new DruidLeaderSelector.Listener()
    {
      @Override
      public void becomeLeader()
      {
        // Not expected
      }

      @Override
      public void stopBeingLeader()
      {
        // Not expected
      }

    };

    leaderSelector.registerListener(listener);

    // Wait a bit for session creation
    Thread.sleep(500);

    // Unregister should destroy the session
    leaderSelector.unregisterListener();

    // Verify session was destroyed
    Assert.assertEquals(SESSION_ID, sessionIdCapture.getValue());

    EasyMock.verify(mockConsulClient);
  }
}
