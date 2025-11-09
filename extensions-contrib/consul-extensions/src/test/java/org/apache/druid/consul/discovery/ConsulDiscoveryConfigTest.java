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
import org.apache.druid.jackson.DefaultObjectMapper;
import org.joda.time.Duration;
import org.junit.Assert;
import org.junit.Test;

public class ConsulDiscoveryConfigTest
{
  private final ObjectMapper jsonMapper = new DefaultObjectMapper();

  @Test
  public void testDefaultValuesSerde() throws Exception
  {
    testSerde("{\"servicePrefix\": \"druid\"}\n");
  }

  @Test
  public void testCustomizedValuesSerde() throws Exception
  {
    testSerde(
        "{\n"
        + "  \"host\": \"consul.example.com\",\n"
        + "  \"port\": 8600,\n"
        + "  \"servicePrefix\": \"test-druid\",\n"
        + "  \"aclToken\": \"secret-token\",\n"
        + "  \"datacenter\": \"dc1\",\n"
        + "  \"healthCheckInterval\": \"PT5S\",\n"
        + "  \"deregisterAfter\": \"PT30S\",\n"
        + "  \"watchSeconds\": \"PT30S\",\n"
        + "  \"maxWatchRetries\": 100,\n"
        + "  \"watchRetryDelay\": \"PT5S\"\n"
        + "}\n"
    );
  }

  @Test
  public void testSslClientConfigSerde() throws Exception
  {
    testSerde(
        "{\n"
        + "  \"host\": \"consul.example.com\",\n"
        + "  \"port\": 8501,\n"
        + "  \"servicePrefix\": \"druid-secure\",\n"
        + "  \"aclToken\": \"secret-token\"\n"
        + "}\n"
    );
  }

  @Test
  public void testBasicAuthConfigurationSerde() throws Exception
  {
    testSerde(
        "{\n"
        + "  \"servicePrefix\": \"druid\",\n"
        + "  \"basicAuthUser\": \"admin\",\n"
        + "  \"basicAuthPassword\": \"secret\"\n"
        + "}\n"
    );
  }

  @Test
  public void testNegativeMaxWatchRetriesMeansUnlimited() throws Exception
  {
    ConsulDiscoveryConfig config = testSerdeAndReturn(
        "{\n"
        + "  \"servicePrefix\": \"druid\",\n"
        + "  \"maxWatchRetries\": -1\n"
        + "}\n"
    );
    Assert.assertEquals(Long.MAX_VALUE, config.getMaxWatchRetries());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNullServicePrefixThrows()
  {
    new ConsulDiscoveryConfig(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyServicePrefixThrows()
  {
    new ConsulDiscoveryConfig(
        null,
        null,
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null
    );
  }

  @Test
  public void testLeaderRetryOverrides() throws Exception
  {
    ConsulDiscoveryConfig config = testSerdeAndReturn(
        "{\n"
        + "  \"servicePrefix\": \"druid\",\n"
        + "  \"leaderMaxErrorRetries\": 5,\n"
        + "  \"leaderRetryBackoffMax\": \"PT30S\"\n"
        + "}\n"
    );
    Assert.assertEquals(5L, config.getLeaderMaxErrorRetries());
    Assert.assertEquals(Duration.millis(30000), config.getLeaderRetryBackoffMax());
  }

  @Test
  public void testToStringMasksSensitiveData()
  {
    ConsulDiscoveryConfig config = new ConsulDiscoveryConfig(
        "localhost",
        8500,
        "druid",
        "secret-acl-token",
        "dc1",
        null,
        null,
        null,
        "admin",
        "password",
        Duration.millis(1000),
        Duration.millis(5000),
        Duration.millis(1000),
        null,
        Duration.millis(1000),
        null,
        null,
        null
    );

    String toString = config.toString();

    Assert.assertFalse(toString.contains("secret-acl-token"));
    Assert.assertFalse(toString.contains("password"));
    Assert.assertFalse(toString.contains("admin"));
    Assert.assertTrue(toString.contains("*****"));
    Assert.assertTrue(toString.contains("localhost"));
    Assert.assertTrue(toString.contains("druid"));
    Assert.assertTrue(toString.contains("8500"));
  }

  private void testSerde(String jsonStr) throws Exception
  {
    ConsulDiscoveryConfig config = jsonMapper.readValue(jsonStr, ConsulDiscoveryConfig.class);
    ConsulDiscoveryConfig roundTrip = jsonMapper.readValue(
        jsonMapper.writeValueAsString(config),
        ConsulDiscoveryConfig.class
    );
    Assert.assertEquals(config, roundTrip);
  }

  private ConsulDiscoveryConfig testSerdeAndReturn(String jsonStr) throws Exception
  {
    ConsulDiscoveryConfig config = jsonMapper.readValue(jsonStr, ConsulDiscoveryConfig.class);
    ConsulDiscoveryConfig roundTrip = jsonMapper.readValue(
        jsonMapper.writeValueAsString(config),
        ConsulDiscoveryConfig.class
    );
    Assert.assertEquals(config, roundTrip);
    return config;
  }
}
