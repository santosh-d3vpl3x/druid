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

package org.apache.druid.server.coordination;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.druid.segment.TestHelper;
import org.junit.Assert;
import org.junit.Test;

public class DruidServerMetadataTest
{
  private final ObjectMapper mapper = TestHelper.makeJsonMapper();

  @Test
  public void testSerdeWithCell() throws Exception
  {
    final DruidServerMetadata expected = new DruidServerMetadata(
        "name",
        "host:8080",
        null,
        100L,
        50L,
        ServerType.HISTORICAL,
        "tier-a",
        "cell-a",
        1
    );

    final DruidServerMetadata actual = mapper.readValue(
        mapper.writeValueAsString(expected),
        DruidServerMetadata.class
    );

    Assert.assertEquals(expected, actual);
    Assert.assertEquals("cell-a", actual.getCell());
    Assert.assertEquals("cell-a", actual.getRoutingCell());
  }

  @Test
  public void testRoutingCellFallsBackToTier() throws Exception
  {
    final DruidServerMetadata expected = new DruidServerMetadata(
        "name",
        "host:8080",
        null,
        100L,
        50L,
        ServerType.HISTORICAL,
        "tier-a",
        1
    );

    final DruidServerMetadata actual = mapper.readValue(
        mapper.writeValueAsString(expected),
        DruidServerMetadata.class
    );

    Assert.assertNull(actual.getCell());
    Assert.assertEquals("tier-a", actual.getRoutingCell());
  }
}
