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
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provider;
import org.apache.druid.client.coordinator.Coordinator;
import org.apache.druid.client.indexing.IndexingService;
import org.apache.druid.discovery.DruidLeaderSelector;
import org.apache.druid.discovery.DruidNodeAnnouncer;
import org.apache.druid.discovery.DruidNodeDiscoveryProvider;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.PolyBind;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.guice.annotations.Self;
import org.apache.druid.initialization.DruidModule;
import org.apache.druid.server.DruidNode;

import java.util.Collections;
import java.util.List;

/**
 * Guice module for Consul-based service discovery and leader election.
 *
 * To use Consul discovery and leader election, set:
 * druid.discovery.type=consul
 * druid.discovery.consul.host=localhost
 * druid.discovery.consul.port=8500
 * druid.discovery.consul.servicePrefix=druid
 *
 * Leader election paths (optional):
 * druid.discovery.consul.coordinatorLeaderLockPath=druid/leader/coordinator
 * druid.discovery.consul.overlordLeaderLockPath=druid/leader/overlord
 */
public class ConsulDiscoveryModule implements DruidModule
{
  private static final String CONSUL_KEY = "consul";

  @Override
  public List<? extends Module> getJacksonModules()
  {
    return Collections.emptyList();
  }

  @Override
  public void configure(Binder binder)
  {
    JsonConfigProvider.bind(binder, "druid.discovery.consul", ConsulDiscoveryConfig.class);

    binder.bind(ConsulApiClient.class)
          .toProvider(new ConsulApiClientProvider())
          .in(LazySingleton.class);

    binder.bind(ConsulClient.class)
          .toProvider(new ConsulClientProvider())
          .in(LazySingleton.class);

    PolyBind.optionBinder(binder, Key.get(DruidNodeDiscoveryProvider.class))
            .addBinding(CONSUL_KEY)
            .to(ConsulDruidNodeDiscoveryProvider.class)
            .in(LazySingleton.class);

    PolyBind.optionBinder(binder, Key.get(DruidNodeAnnouncer.class))
            .addBinding(CONSUL_KEY)
            .to(ConsulDruidNodeAnnouncer.class)
            .in(LazySingleton.class);

    // Coordinator leader election
    PolyBind.optionBinder(binder, Key.get(DruidLeaderSelector.class, Coordinator.class))
            .addBinding(CONSUL_KEY)
            .toProvider(new CoordinatorLeaderSelectorProvider())
            .in(LazySingleton.class);

    // Overlord leader election
    PolyBind.optionBinder(binder, Key.get(DruidLeaderSelector.class, IndexingService.class))
            .addBinding(CONSUL_KEY)
            .toProvider(new OverlordLeaderSelectorProvider())
            .in(LazySingleton.class);
  }

  private static class ConsulApiClientProvider implements Provider<ConsulApiClient>
  {
    private ConsulDiscoveryConfig config;
    private ObjectMapper jsonMapper;

    @Inject
    public void configure(
        ConsulDiscoveryConfig config,
        @Json ObjectMapper jsonMapper
    )
    {
      this.config = config;
      this.jsonMapper = jsonMapper;
    }

    @Override
    public ConsulApiClient get()
    {
      return new DefaultConsulApiClient(config, jsonMapper);
    }
  }

  private static class ConsulClientProvider implements Provider<ConsulClient>
  {
    private ConsulDiscoveryConfig config;

    @Inject
    public void configure(ConsulDiscoveryConfig config)
    {
      this.config = config;
    }

    @Override
    public ConsulClient get()
    {
      // Create ConsulClient for leader election (ACL token passed per-request via QueryParams)
      return new ConsulClient(config.getHost(), config.getPort());
    }
  }

  private static class CoordinatorLeaderSelectorProvider implements Provider<DruidLeaderSelector>
  {
    private DruidNode self;
    private ConsulDiscoveryConfig config;
    private ConsulClient consulClient;

    @Inject
    public void configure(
        @Self DruidNode self,
        ConsulDiscoveryConfig config,
        ConsulClient consulClient
    )
    {
      this.self = self;
      this.config = config;
      this.consulClient = consulClient;
    }

    @Override
    public DruidLeaderSelector get()
    {
      return new ConsulLeaderSelector(
          self,
          config.getCoordinatorLeaderLockPath(),
          config,
          consulClient
      );
    }
  }

  private static class OverlordLeaderSelectorProvider implements Provider<DruidLeaderSelector>
  {
    private DruidNode self;
    private ConsulDiscoveryConfig config;
    private ConsulClient consulClient;

    @Inject
    public void configure(
        @Self DruidNode self,
        ConsulDiscoveryConfig config,
        ConsulClient consulClient
    )
    {
      this.self = self;
      this.config = config;
      this.consulClient = consulClient;
    }

    @Override
    public DruidLeaderSelector get()
    {
      return new ConsulLeaderSelector(
          self,
          config.getOverlordLeaderLockPath(),
          config,
          consulClient
      );
    }
  }
}
