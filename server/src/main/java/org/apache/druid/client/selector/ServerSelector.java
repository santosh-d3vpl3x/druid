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

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import org.apache.druid.client.DataSegmentInterner;
import org.apache.druid.client.QueryableDruidServer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.CloneQueryMode;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.server.coordination.DruidServerMetadata;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.Overshadowable;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 */
public class ServerSelector implements Overshadowable<ServerSelector>
{
  private static final Logger LOG = new Logger(ServerSelector.class);

  @GuardedBy("this")
  private final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> historicalServers;

  @GuardedBy("this")
  private final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> realtimeServers;

  private final TierSelectorStrategy historicalTierStrategy;
  private final TierSelectorStrategy realtimeTierStrategy;

  private final AtomicReference<DataSegment> segment;

  private final HistoricalFilter filter;

  @VisibleForTesting
  public ServerSelector(
      DataSegment segment,
      TierSelectorStrategy historicalTierStrategy,
      HistoricalFilter filter
  )
  {
    this(segment, historicalTierStrategy, historicalTierStrategy, filter);
  }

  public ServerSelector(
      DataSegment segment,
      TierSelectorStrategy historicalTierStrategy,
      TierSelectorStrategy realtimeTierStrategy,
      HistoricalFilter filter
  )
  {
    this.segment = new AtomicReference<>(DataSegmentInterner.intern(segment));
    this.historicalTierStrategy = historicalTierStrategy;
    this.realtimeTierStrategy = realtimeTierStrategy;
    this.historicalServers = new Int2ObjectRBTreeMap<>(historicalTierStrategy.getComparator());
    this.realtimeServers = new Int2ObjectRBTreeMap<>(this.realtimeTierStrategy.getComparator());
    this.filter = filter;
  }

  public DataSegment getSegment()
  {
    return segment.get();
  }

  public void addServerAndUpdateSegment(QueryableDruidServer server, DataSegment segment)
  {
    synchronized (this) {
      this.segment.set(segment);
      Set<QueryableDruidServer> priorityServers;
      if (server.getServer().getType() == ServerType.HISTORICAL) {
        priorityServers = historicalServers.computeIfAbsent(
            server.getServer().getPriority(),
            p -> new HashSet<>()
        );
      } else {
        priorityServers = realtimeServers.computeIfAbsent(
            server.getServer().getPriority(),
            p -> new HashSet<>()
        );
      }
      priorityServers.add(server);
    }
  }

  public boolean removeServer(QueryableDruidServer server)
  {
    synchronized (this) {
      Int2ObjectRBTreeMap<Set<QueryableDruidServer>> servers;
      Set<QueryableDruidServer> priorityServers;
      int priority = server.getServer().getPriority();
      if (server.getServer().getType() == ServerType.HISTORICAL) {
        servers = historicalServers;
        priorityServers = historicalServers.get(priority);
      } else {
        servers = realtimeServers;
        priorityServers = realtimeServers.get(priority);
      }

      if (priorityServers == null) {
        return false;
      }

      boolean result = priorityServers.remove(server);

      if (priorityServers.isEmpty()) {
        servers.remove(priority);
      }
      return result;
    }
  }

  public boolean isEmpty()
  {
    synchronized (this) {
      return historicalServers.isEmpty() && realtimeServers.isEmpty();
    }
  }

  public List<DruidServerMetadata> getCandidates(
      final int numCandidates,
      final CloneQueryMode cloneQueryMode
  )
  {
    return getCandidates(null, numCandidates, cloneQueryMode);
  }

  public List<DruidServerMetadata> getCandidates(
      @Nullable final Query<?> query,
      final int numCandidates,
      final CloneQueryMode cloneQueryMode
  )
  {
    List<DruidServerMetadata> candidates;
    synchronized (this) {
      final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> filteredHistoricals =
          getCellFilteredHistoricals(query, filter.getQueryableServers(historicalServers, cloneQueryMode));
      if (numCandidates > 0) {
        candidates = new ArrayList<>(numCandidates);
        historicalTierStrategy.pick(filteredHistoricals, segment.get(), numCandidates)
                              .stream()
                              .map(server -> server.getServer().getMetadata())
                              .forEach(candidates::add);

        final boolean shouldIncludeRealtimeCandidates =
            query == null
            || (filteredHistoricals.isEmpty() && (historicalServers.isEmpty() || isRealtimeFallbackAllowed(query)));
        if (candidates.size() < numCandidates && shouldIncludeRealtimeCandidates) { //-V6007: false alarm due to a bug in PVS-Studio
          realtimeTierStrategy.pick(realtimeServers, segment.get(), numCandidates - candidates.size())
                              .stream()
                              .map(server -> server.getServer().getMetadata())
                              .forEach(candidates::add);
        }
        return candidates;
      } else {
        return getAllServers(query, cloneQueryMode);
      }
    }
  }

  public List<DruidServerMetadata> getAllServers(CloneQueryMode cloneQueryMode)
  {
    return getAllServers(null, cloneQueryMode);
  }

  public List<DruidServerMetadata> getAllServers(@Nullable final Query<?> query, CloneQueryMode cloneQueryMode)
  {
    final List<DruidServerMetadata> servers = new ArrayList<>();

    synchronized (this) {
      final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> filteredHistoricals =
          getCellFilteredHistoricals(query, filter.getQueryableServers(historicalServers, cloneQueryMode));
      filteredHistoricals
            .values()
            .stream()
            .flatMap(Collection::stream)
            .map(server -> server.getServer().getMetadata())
            .forEach(servers::add);

      if (
          query == null
          || (filteredHistoricals.isEmpty() && (historicalServers.isEmpty() || isRealtimeFallbackAllowed(query)))
      ) {
        realtimeServers.values()
                       .stream()
                       .flatMap(Collection::stream)
                       .map(server -> server.getServer().getMetadata())
                       .forEach(servers::add);
      }
    }

    return servers;
  }

  @Nullable
  public <T> QueryableDruidServer pick(@Nullable Query<T> query, CloneQueryMode cloneQueryMode)
  {
    synchronized (this) {
      final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> filteredHistoricals =
          getCellFilteredHistoricals(query, filter.getQueryableServers(historicalServers, cloneQueryMode));
      if (!filteredHistoricals.isEmpty()) {
        return historicalTierStrategy.pick(query, filteredHistoricals, segment.get());
      }
      if (
          query != null
          && query.context().getBoolean(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, false)
          && !realtimeServers.isEmpty()
      ) {
        LOG.info(
            "Using realtime fallback for queryId[%s], cell[%s], mode[%s], crossCell[true]",
            query.getId(),
            query.context().getString(QueryContexts.CTX_CELL),
            query.context().getString(QueryContexts.CTX_CELL_EXECUTION_MODE)
        );
        return realtimeTierStrategy.pick(query, realtimeServers, segment.get());
      }
      if (!historicalServers.isEmpty()) {
        return null;
      }
      return realtimeTierStrategy.pick(query, realtimeServers, segment.get());
    }
  }

  private <T> Int2ObjectRBTreeMap<Set<QueryableDruidServer>> getCellFilteredHistoricals(
      @Nullable final Query<T> query,
      final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> candidateHistoricals
  )
  {
    if (query == null) {
      return candidateHistoricals;
    }

    final QueryContexts.CellExecutionMode mode = query.context().getEnum(
        QueryContexts.CTX_CELL_EXECUTION_MODE,
        QueryContexts.CellExecutionMode.class,
        QueryContexts.DEFAULT_CELL_EXECUTION_MODE
    );
    final String cell = query.context().getString(QueryContexts.CTX_CELL);
    if (cell == null || mode != QueryContexts.CellExecutionMode.STRICT_CELL) {
      return candidateHistoricals;
    }

    final Int2ObjectRBTreeMap<Set<QueryableDruidServer>> cellFilteredHistoricals =
        new Int2ObjectRBTreeMap<>(candidateHistoricals.comparator());
    for (final Int2ObjectMap.Entry<Set<QueryableDruidServer>> entry : candidateHistoricals.int2ObjectEntrySet()) {
      final Set<QueryableDruidServer> matchingServers = entry.getValue()
                                                              .stream()
                                                              .filter(server -> cell.equals(server.getServer().getTier()))
                                                              .collect(Collectors.toSet());
      if (!matchingServers.isEmpty()) {
        cellFilteredHistoricals.put(entry.getIntKey(), matchingServers);
      }
    }
    return cellFilteredHistoricals;
  }

  private boolean isRealtimeFallbackAllowed(@Nullable final Query<?> query)
  {
    return query != null && query.context().getBoolean(QueryContexts.CTX_ALLOW_REALTIME_EXCEPTION, false);
  }

  @Override
  public boolean overshadows(ServerSelector other)
  {
    final DataSegment thisSegment = segment.get();
    final DataSegment thatSegment = other.getSegment();
    return thisSegment.overshadows(thatSegment);
  }

  @Override
  public int getStartRootPartitionId()
  {
    return segment.get().getStartRootPartitionId();
  }

  @Override
  public int getEndRootPartitionId()
  {
    return segment.get().getEndRootPartitionId();
  }

  @Override
  public String getVersion()
  {
    return segment.get().getVersion();
  }

  @Override
  public short getMinorVersion()
  {
    return segment.get().getMinorVersion();
  }

  @Override
  public short getAtomicUpdateGroupSize()
  {
    return segment.get().getAtomicUpdateGroupSize();
  }

  @Override
  public boolean hasData()
  {
    return segment.get().hasData();
  }

  /**
   * Checks if the segment is currently served by a realtime server, and is not served by a historical.
   */
  public boolean isRealtimeSegment()
  {
    synchronized (this) {
      return (!realtimeServers.isEmpty()) && historicalServers.isEmpty();
    }
  }
}
