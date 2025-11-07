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
    testSerde(
        "{\"servicePrefix\": \"druid\"}\n",
        new ConsulDiscoveryConfig(null, null, "druid", null, null, null, null, null, null, null)
    );
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
        + "}\n",
        new ConsulDiscoveryConfig(
            "consul.example.com",
            8600,
            "test-druid",
            "secret-token",
            "dc1",
            Duration.millis(5000),
            Duration.millis(30000),
            Duration.millis(30000),
            100L,
            Duration.millis(5000)
        )
    );
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNullServicePrefixThrows()
  {
    new ConsulDiscoveryConfig(null, null, null, null, null, null, null, null, null, null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void testEmptyServicePrefixThrows()
  {
    new ConsulDiscoveryConfig(null, null, "", null, null, null, null, null, null, null);
  }

  private void testSerde(String jsonStr, ConsulDiscoveryConfig expected) throws Exception
  {
    ConsulDiscoveryConfig actual = jsonMapper.readValue(
        jsonMapper.writeValueAsString(
            jsonMapper.readValue(jsonStr, ConsulDiscoveryConfig.class)
        ),
        ConsulDiscoveryConfig.class
    );

    Assert.assertEquals(expected, actual);
  }
}
