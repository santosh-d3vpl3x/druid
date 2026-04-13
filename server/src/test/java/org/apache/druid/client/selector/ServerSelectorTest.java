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

package org.apache.druid.client.selector;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.client.DirectDruidClient;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.QueryableDruidServer;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.query.CloneQueryMode;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.dimension.DefaultDimensionSpec;
import org.apache.druid.query.groupby.GroupByQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.NoneShardSpec;
import org.apache.druid.timeline.partition.TombstoneShardSpec;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ServerSelectorTest
{
  private static final Query SAMPLE_GROUPBY_QUERY = GroupByQuery.builder()
                                                                .setDataSource("foo")
                                                                .setInterval(new MultipleIntervalSegmentSpec(ImmutableList.of(Intervals.of("2000/3000"))))
                                                                .setGranularity(Granularities.ALL)
                                                                .setDimensions(new DefaultDimensionSpec("dim2", "d0"))
                                                                .build();

  @Before
  public void setUp()
  {
    TierSelectorStrategy tierSelectorStrategy = EasyMock.createMock(TierSelectorStrategy.class);
    EasyMock.expect(tierSelectorStrategy.getComparator()).andReturn(Integer::compare).anyTimes();
  }

  @Test
  public void testSegmentUpdate()
  {
    final ServerSelector selector = new ServerSelector(
        DataSegment.builder()
                   .dataSource("test_broker_server_view")
                   .interval(Intervals.of("2012/2013"))
                   .loadSpec(
                       ImmutableMap.of(
                           "type",
                           "local",
                           "path",
                           "somewhere"
                       )
                   )
                   .version("v1")
                   .dimensions(ImmutableList.of())
                   .metrics(ImmutableList.of())
                   .shardSpec(NoneShardSpec.instance())
                   .binaryVersion(9)
                   .size(0)
                   .build(),
        new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    selector.addServerAndUpdateSegment(
        new QueryableDruidServer(
            new DruidServer("test1", "localhost", null, 0, null, ServerType.HISTORICAL, DruidServer.DEFAULT_TIER, 1),
            EasyMock.createMock(DirectDruidClient.class)
        ),
        DataSegment.builder()
                   .dataSource(
                       "test_broker_server_view")
                   .interval(Intervals.of("2012/2013"))
                   .loadSpec(
                       ImmutableMap.of(
                           "type",
                           "local",
                           "path",
                           "somewhere"
                       )
                   )
                   .version("v1")
                   .dimensions(
                       ImmutableList.of(
                           "a",
                           "b",
                           "c"
                       ))
                   .metrics(
                       ImmutableList.of())
                   .shardSpec(NoneShardSpec.instance())
                   .binaryVersion(9)
                   .size(0)
                   .build()
    );

    Assert.assertEquals(ImmutableList.of("a", "b", "c"), selector.getSegment().getDimensions());
  }

  @Test(expected = NullPointerException.class)
  public void testSegmentCannotBeNull()
  {
    final ServerSelector selector = new ServerSelector(
        null,
        new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );
  }

  @Test
  public void testSegmentWithNoData()
  {
    final ServerSelector selector = new ServerSelector(
        DataSegment.builder()
                   .dataSource("test_broker_server_view")
                   .interval(Intervals.of("2012/2013"))
                   .loadSpec(
                       ImmutableMap.of(
                           "type",
                           "tombstone"
                       )
                   )
                   .version("v1")
                   .dimensions(ImmutableList.of())
                   .metrics(ImmutableList.of())
                   .shardSpec(new TombstoneShardSpec())
                   .binaryVersion(9)
                   .size(0)
                   .build(),
        new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );
    Assert.assertFalse(selector.hasData());
  }

  @Test
  public void testSegmentWithData()
  {
    final ServerSelector selector = new ServerSelector(
        DataSegment.builder()
                   .dataSource("another segment") // fool the interner inside the selector
                   .interval(Intervals.of("2012/2013"))
                   .loadSpec(
                       ImmutableMap.of(
                           "type",
                           "local",
                           "path",
                           "somewhere"
                       )
                   )
                   .version("v1")
                   .dimensions(ImmutableList.of())
                   .metrics(ImmutableList.of())
                   .shardSpec(NoneShardSpec.instance())
                   .binaryVersion(9)
                   .size(0)
                   .build(),
        new HighestPriorityTierSelectorStrategy(new RandomServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );
    Assert.assertTrue(selector.hasData());
  }

  @Test
  public void testPickStrictCellChoosesMatchingHistoricalTier()
  {
    final ServerSelector selector = new ServerSelector(
        makeDataSegment("strict-cell-tier"),
        new HighestPriorityTierSelectorStrategy(new ConnectionCountServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    final QueryableDruidServer cellA = makeServer("cell-a", ServerType.HISTORICAL, "us-east-1a", 1);
    final QueryableDruidServer cellB = makeServer("cell-b", ServerType.HISTORICAL, "us-east-1b", 1);
    selector.addServerAndUpdateSegment(cellA, makeDataSegment("strict-cell-tier"));
    selector.addServerAndUpdateSegment(cellB, makeDataSegment("strict-cell-tier"));

    final Query<?> strictCellQuery = SAMPLE_GROUPBY_QUERY.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "us-east-1b",
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.STRICT_CELL.name()
        )
    );

    Assert.assertEquals(cellB, selector.pick(strictCellQuery, CloneQueryMode.EXCLUDECLONES));
  }

  @Test
  public void testPickStrictCellFallsBackToRealtimeWhenAllowed()
  {
    final ServerSelector selector = new ServerSelector(
        makeDataSegment("strict-cell-realtime"),
        new HighestPriorityTierSelectorStrategy(new ConnectionCountServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    final QueryableDruidServer historical = makeServer("cell-a", ServerType.HISTORICAL, "us-east-1a", 1);
    final QueryableDruidServer realtime = makeServer("realtime", ServerType.REALTIME, DruidServer.DEFAULT_TIER, 0);
    selector.addServerAndUpdateSegment(historical, makeDataSegment("strict-cell-realtime"));
    selector.addServerAndUpdateSegment(realtime, makeDataSegment("strict-cell-realtime"));

    final Query<?> strictCellQueryWithRealtimeFallback = SAMPLE_GROUPBY_QUERY.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "us-west-2a",
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.STRICT_CELL.name(),
            QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, true
        )
    );

    Assert.assertEquals(realtime, selector.pick(strictCellQueryWithRealtimeFallback, CloneQueryMode.EXCLUDECLONES));
  }

  @Test
  public void testGetCandidatesStrictCellFiltersHistoricals()
  {
    final ServerSelector selector = new ServerSelector(
        makeDataSegment("strict-cell-candidates"),
        new HighestPriorityTierSelectorStrategy(new ConnectionCountServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    final QueryableDruidServer cellA = makeServer("cell-a", ServerType.HISTORICAL, "us-east-1a", 1);
    final QueryableDruidServer cellB = makeServer("cell-b", ServerType.HISTORICAL, "us-east-1b", 1);
    final QueryableDruidServer realtime = makeServer("realtime", ServerType.REALTIME, DruidServer.DEFAULT_TIER, 0);
    selector.addServerAndUpdateSegment(cellA, makeDataSegment("strict-cell-candidates"));
    selector.addServerAndUpdateSegment(cellB, makeDataSegment("strict-cell-candidates"));
    selector.addServerAndUpdateSegment(realtime, makeDataSegment("strict-cell-candidates"));

    final Query<?> strictCellQuery = SAMPLE_GROUPBY_QUERY.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "us-east-1b",
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.STRICT_CELL.name()
        )
    );

    Assert.assertEquals(
        ImmutableList.of(cellB.getServer().getMetadata()),
        selector.getCandidates(strictCellQuery, 2, CloneQueryMode.EXCLUDECLONES)
    );
    Assert.assertEquals(
        ImmutableList.of(cellB.getServer().getMetadata()),
        selector.getAllServers(strictCellQuery, CloneQueryMode.EXCLUDECLONES)
    );
  }

  @Test
  public void testGetCandidatesStrictCellIncludesRealtimeOnlyWhenAllowed()
  {
    final ServerSelector selector = new ServerSelector(
        makeDataSegment("strict-cell-candidates-realtime"),
        new HighestPriorityTierSelectorStrategy(new ConnectionCountServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    final QueryableDruidServer historical = makeServer("cell-a", ServerType.HISTORICAL, "us-east-1a", 1);
    final QueryableDruidServer realtime = makeServer("realtime", ServerType.REALTIME, DruidServer.DEFAULT_TIER, 0);
    selector.addServerAndUpdateSegment(historical, makeDataSegment("strict-cell-candidates-realtime"));
    selector.addServerAndUpdateSegment(realtime, makeDataSegment("strict-cell-candidates-realtime"));

    final Query<?> strictCellQueryNoFallback = SAMPLE_GROUPBY_QUERY.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "us-west-2a",
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.STRICT_CELL.name()
        )
    );
    final Query<?> strictCellQueryFallback = strictCellQueryNoFallback.withOverriddenContext(
        ImmutableMap.of(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, true)
    );

    Assert.assertEquals(
        ImmutableList.of(),
        selector.getCandidates(strictCellQueryNoFallback, 1, CloneQueryMode.EXCLUDECLONES)
    );
    Assert.assertEquals(
        ImmutableList.of(realtime.getServer().getMetadata()),
        selector.getCandidates(strictCellQueryFallback, 1, CloneQueryMode.EXCLUDECLONES)
    );
  }

  @Test
  public void testStrictAndFailoverRoutingFlow()
  {
    final ServerSelector selector = new ServerSelector(
        makeDataSegment("strict-failover-flow"),
        new HighestPriorityTierSelectorStrategy(new ConnectionCountServerSelectorStrategy()),
        HistoricalFilter.IDENTITY_FILTER
    );

    final QueryableDruidServer historical = makeServer("cell-a", ServerType.HISTORICAL, "us-east-1a", 1);
    final QueryableDruidServer realtime = makeServer("realtime", ServerType.REALTIME, DruidServer.DEFAULT_TIER, 0);
    selector.addServerAndUpdateSegment(historical, makeDataSegment("strict-failover-flow"));
    selector.addServerAndUpdateSegment(realtime, makeDataSegment("strict-failover-flow"));

    final Query<?> strictNoFallback = SAMPLE_GROUPBY_QUERY.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "us-west-2a",
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.STRICT_CELL.name()
        )
    );
    final Query<?> strictWithFallback = strictNoFallback.withOverriddenContext(
        ImmutableMap.of(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, true)
    );
    final Query<?> failoverQuery = strictNoFallback.withOverriddenContext(
        ImmutableMap.of(
            QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.CELL_FAILOVER.name(),
            QueryContexts.CTX_FAILOVER_REASON, "AZ outage",
            QueryContexts.CTX_FAILOVER_TICKET, "INC-12345"
        )
    );

    Assert.assertNull(selector.pick(strictNoFallback, CloneQueryMode.EXCLUDECLONES));
    Assert.assertEquals(realtime, selector.pick(strictWithFallback, CloneQueryMode.EXCLUDECLONES));
    Assert.assertEquals(historical, selector.pick(failoverQuery, CloneQueryMode.EXCLUDECLONES));
  }

  private static QueryableDruidServer makeServer(
      final String name,
      final ServerType serverType,
      final String tier,
      final int priority
  )
  {
    return new QueryableDruidServer(
        new DruidServer(name, "localhost", null, 0, null, serverType, tier, priority),
        EasyMock.createMock(DirectDruidClient.class)
    );
  }

  private static DataSegment makeDataSegment(final String dataSource)
  {
    return DataSegment.builder()
                      .dataSource(dataSource)
                      .interval(Intervals.of("2012/2013"))
                      .loadSpec(ImmutableMap.of("type", "local", "path", "somewhere"))
                      .version("v1")
                      .dimensions(ImmutableList.of())
                      .metrics(ImmutableList.of())
                      .shardSpec(NoneShardSpec.instance())
                      .binaryVersion(9)
                      .size(0)
                      .build();
  }
}
