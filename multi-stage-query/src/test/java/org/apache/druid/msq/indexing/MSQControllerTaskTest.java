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

package org.apache.druid.msq.indexing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.apache.druid.error.DruidException;
import org.apache.druid.indexing.common.TaskLock;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.TimeChunkLock;
import org.apache.druid.indexing.common.actions.TaskAction;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.actions.TimeChunkLockTryAcquireAction;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.msq.indexing.destination.DataSourceMSQDestination;
import org.apache.druid.query.BadQueryContextException;
import org.apache.druid.query.Druids;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.scan.ScanQuery;
import org.apache.druid.query.spec.MultipleIntervalSegmentSpec;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.server.coordination.BroadcastDatasourceLoadingSpec;
import org.apache.druid.server.lookup.cache.LookupLoadingSpec;
import org.apache.druid.sql.calcite.planner.ColumnMapping;
import org.apache.druid.sql.calcite.planner.ColumnMappings;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MSQControllerTaskTest
{
  private static final List<Interval> INTERVALS = Collections.singletonList(
      Intervals.of("2011-04-01/2011-04-03")
  );
  private static final ObjectMapper JSON_MAPPER = TestHelper.makeJsonMapper();

  private static LegacyMSQSpec.Builder msqSpecBuilder()
  {
    return LegacyMSQSpec
        .builder()
        .destination(
            new DataSourceMSQDestination("target", Granularities.DAY, null, INTERVALS, null, null, null)
        )
        .query(
            new Druids.ScanQueryBuilder()
                .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
                .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
                .dataSource("target")
                .build()
        )
        .columnMappings(new ColumnMappings(ImmutableList.of(new ColumnMapping("a0", "cnt"))))
        .tuningConfig(MSQTuningConfig.defaultConfig());
  }

  @Test
  public void testGetInputSourceResources()
  {
    Assert.assertTrue(createControllerTask(msqSpecBuilder()).getInputSourceResources().isEmpty());
  }

  @Test
  public void testGetDefaultLookupLoadingSpec()
  {
    MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder());
    Assert.assertEquals(LookupLoadingSpec.NONE, controllerTask.getLookupLoadingSpec());
  }

  @Test
  public void testGetDefaultBroadcastDatasourceLoadingSpec()
  {
    MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder());
    Assert.assertEquals(BroadcastDatasourceLoadingSpec.NONE, controllerTask.getBroadcastDatasourceLoadingSpec());
  }

  @Test
  public void testGetLookupLoadingSpecUsingLookupLoadingInfoInContext()
  {
    LegacyMSQSpec.Builder builder = LegacyMSQSpec
        .builder()
        .query(new Druids.ScanQueryBuilder()
                   .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
                   .dataSource("target")
                   .context(
                       ImmutableMap.of(
                           LookupLoadingSpec.CTX_LOOKUPS_TO_LOAD, Arrays.asList("lookupName1", "lookupName2"),
                           LookupLoadingSpec.CTX_LOOKUP_LOADING_MODE, LookupLoadingSpec.Mode.ONLY_REQUIRED)
                   )
                   .build()
        )
        .columnMappings(new ColumnMappings(Collections.emptyList()))
        .tuningConfig(MSQTuningConfig.defaultConfig());

    // Validate that MSQ Controller task doesn't load any lookups even if context has lookup info populated.
    Assert.assertEquals(LookupLoadingSpec.NONE, createControllerTask(builder).getLookupLoadingSpec());
  }

  @Test
  public void testGetTaskAllocatorId()
  {
    MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder());
    Assert.assertEquals(controllerTask.getId(), controllerTask.getTaskAllocatorId());
  }

  @Test
  public void testGetTaskLockType()
  {
    final DataSourceMSQDestination appendDestination
        = new DataSourceMSQDestination("target", Granularities.DAY, null, null, null, null, null);
    Assert.assertEquals(
        TaskLockType.SHARED,
        createControllerTask(msqSpecBuilder().destination(appendDestination)).getTaskLockType()
    );

    final DataSourceMSQDestination replaceDestination
        = new DataSourceMSQDestination("target", Granularities.DAY, null, INTERVALS, null, null, null);
    Assert.assertEquals(
        TaskLockType.EXCLUSIVE,
        createControllerTask(msqSpecBuilder().destination(replaceDestination)).getTaskLockType()
    );

    // With 'useConcurrentLocks' in task context
    final Map<String, Object> taskContext = Collections.singletonMap(Tasks.USE_CONCURRENT_LOCKS, true);
    final MSQControllerTask appendTaskWithContext = new MSQControllerTask(
        null,
        msqSpecBuilder().destination(appendDestination).build(),
        null,
        null,
        null,
        null,
        null,
        taskContext
    );
    Assert.assertEquals(TaskLockType.APPEND, appendTaskWithContext.getTaskLockType());

    final MSQControllerTask replaceTaskWithContext = new MSQControllerTask(
        null,
        msqSpecBuilder().destination(replaceDestination).build(),
        null,
        null,
        null,
        null,
        null,
        taskContext
    );
    Assert.assertEquals(TaskLockType.REPLACE, replaceTaskWithContext.getTaskLockType());

    // With 'useConcurrentLocks' in query context
    final Map<String, Object> queryContext = Collections.singletonMap(Tasks.USE_CONCURRENT_LOCKS, true);
    final ScanQuery query = new Druids.ScanQueryBuilder()
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
        .dataSource("target")
        .context(queryContext)
        .build();
    Assert.assertEquals(
        TaskLockType.APPEND,
        createControllerTask(msqSpecBuilder().query(query).destination(appendDestination)).getTaskLockType()
    );
    Assert.assertEquals(
        TaskLockType.REPLACE,
        createControllerTask(msqSpecBuilder().query(query).destination(replaceDestination)).getTaskLockType()
    );
  }

  @Test
  public void testIsReady() throws Exception
  {
    TestTaskActionClient taskActionClient = new TestTaskActionClient(
        new TimeChunkLock(
            TaskLockType.REPLACE,
            "groupId",
            "dataSource",
            INTERVALS.get(0),
            "0",
            0
        )
    );
    Assert.assertTrue(createControllerTask(msqSpecBuilder()).isReady(taskActionClient));
  }

  @Test
  public void testIsReadyWithRevokedLock()
  {
    MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder());
    TaskActionClient taskActionClient = new TestTaskActionClient(
        new TimeChunkLock(
            TaskLockType.REPLACE,
            "groupId",
            "dataSource",
            INTERVALS.get(0),
            "0",
            0,
            true
        )
    );
    DruidException exception = Assert.assertThrows(
        DruidException.class,
        () -> controllerTask.isReady(taskActionClient)
    );
    Assert.assertEquals(
        "Lock of type[REPLACE] for interval[2011-04-01T00:00:00.000Z/2011-04-03T00:00:00.000Z] was revoked",
        exception.getMessage()
    );
  }

  @Test
  public void testConstructorNormalizesCellContext()
  {
    final ScanQuery query = new Druids.ScanQueryBuilder()
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
        .dataSource("target")
        .context(
            ImmutableMap.of(
                QueryContexts.CTX_CELL, "  us-east-1a ",
                QueryContexts.CTX_CELL_EXECUTION_MODE, "strict_cell"
            )
        )
        .build();

    final MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder().query(query));
    Assert.assertEquals("us-east-1a", controllerTask.getQuerySpec().getContext().getString(QueryContexts.CTX_CELL));
    Assert.assertEquals(
        QueryContexts.CellExecutionMode.STRICT_CELL.name(),
        controllerTask.getQuerySpec().getContext().getString(QueryContexts.CTX_CELL_EXECUTION_MODE)
    );
    Assert.assertEquals(
        false,
        controllerTask.getQuerySpec().getContext().getBoolean(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, true)
    );
    Assert.assertEquals("us-east-1a", controllerTask.getContextValue(QueryContexts.CTX_CELL));
    Assert.assertEquals(
        QueryContexts.CellExecutionMode.STRICT_CELL.name(),
        controllerTask.getContextValue(QueryContexts.CTX_CELL_EXECUTION_MODE)
    );
    Assert.assertEquals(false, controllerTask.getContextValue(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION));
  }

  @Test
  public void testConstructorPropagatesFailoverContextToTaskContext()
  {
    final ScanQuery query = new Druids.ScanQueryBuilder()
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
        .dataSource("target")
        .context(
            ImmutableMap.of(
                QueryContexts.CTX_CELL, "us-east-1a",
                QueryContexts.CTX_CELL_EXECUTION_MODE, QueryContexts.CellExecutionMode.CELL_FAILOVER.name(),
                QueryContexts.CTX_FAILOVER_REASON, "capacity-pressure",
                QueryContexts.CTX_FAILOVER_TICKET, "INC-1234"
            )
        )
        .build();

    final MSQControllerTask controllerTask = createControllerTask(msqSpecBuilder().query(query));
    Assert.assertEquals("us-east-1a", controllerTask.getContextValue(QueryContexts.CTX_CELL));
    Assert.assertEquals(
        QueryContexts.CellExecutionMode.CELL_FAILOVER.name(),
        controllerTask.getContextValue(QueryContexts.CTX_CELL_EXECUTION_MODE)
    );
    Assert.assertEquals("capacity-pressure", controllerTask.getContextValue(QueryContexts.CTX_FAILOVER_REASON));
    Assert.assertEquals("INC-1234", controllerTask.getContextValue(QueryContexts.CTX_FAILOVER_TICKET));
  }

  @Test
  public void testConstructorNormalizesSqlQueryContext()
  {
    final ScanQuery query = new Druids.ScanQueryBuilder()
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
        .dataSource("target")
        .build();

    final MSQControllerTask controllerTask = new MSQControllerTask(
        "controller_1",
        msqSpecBuilder().query(query).build(),
        "select 1",
        ImmutableMap.of(
            QueryContexts.CTX_CELL, "  us-east-1a ",
            QueryContexts.CTX_CELL_EXECUTION_MODE, "strict_cell"
        ),
        null,
        null,
        null,
        null
    );

    final Map<String, Object> taskAsMap = JSON_MAPPER.convertValue(controllerTask, Map.class);
    final Map<String, Object> serializedSqlContext = (Map<String, Object>) taskAsMap.get("sqlQueryContext");
    Assert.assertEquals("us-east-1a", serializedSqlContext.get(QueryContexts.CTX_CELL));
    Assert.assertEquals(
        QueryContexts.CellExecutionMode.STRICT_CELL.name(),
        serializedSqlContext.get(QueryContexts.CTX_CELL_EXECUTION_MODE)
    );
    Assert.assertEquals(false, serializedSqlContext.get(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION));
  }

  @Test
  public void testConstructorRejectsInvalidCellContext()
  {
    final ScanQuery query = new Druids.ScanQueryBuilder()
        .resultFormat(ScanQuery.ResultFormat.RESULT_FORMAT_COMPACTED_LIST)
        .intervals(new MultipleIntervalSegmentSpec(INTERVALS))
        .dataSource("target")
        .context(ImmutableMap.of(QueryContexts.CTX_CELL_EXECUTION_MODE, "STRICT_CELL"))
        .build();

    final BadQueryContextException exception = Assert.assertThrows(
        BadQueryContextException.class,
        () -> createControllerTask(msqSpecBuilder().query(query))
    );
    Assert.assertTrue(exception.getMessage().contains("Expected key [cell] to be a non-empty String"));
  }

  private static MSQControllerTask createControllerTask(LegacyMSQSpec.Builder specBuilder)
  {
    return new MSQControllerTask("controller_1", specBuilder.build(), null, null, null, null, null, null, null);
  }

  private static class TestTaskActionClient implements TaskActionClient
  {
    private final TaskLock taskLock;

    TestTaskActionClient(TaskLock taskLock)
    {
      this.taskLock = taskLock;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <RetType> RetType submit(TaskAction<RetType> taskAction)
    {
      if (!(taskAction instanceof TimeChunkLockTryAcquireAction)) {
        throw new ISE("action[%s] is not supported", taskAction);
      }
      return (RetType) taskLock;
    }
  }
}
