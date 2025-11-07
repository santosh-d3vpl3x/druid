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

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Binder;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.Provider;
import org.apache.druid.discovery.DruidNodeAnnouncer;
import org.apache.druid.discovery.DruidNodeDiscoveryProvider;
import org.apache.druid.guice.JsonConfigProvider;
import org.apache.druid.guice.LazySingleton;
import org.apache.druid.guice.PolyBind;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.initialization.DruidModule;

import java.util.Collections;
import java.util.List;

/**
 * Guice module for Consul-based service discovery.
 *
 * To use Consul discovery, set:
 * druid.discovery.type=consul
 * druid.discovery.consul.host=localhost
 * druid.discovery.consul.port=8500
 * druid.discovery.consul.servicePrefix=druid
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

    PolyBind.optionBinder(binder, Key.get(DruidNodeDiscoveryProvider.class))
            .addBinding(CONSUL_KEY)
            .to(ConsulDruidNodeDiscoveryProvider.class)
            .in(LazySingleton.class);

    PolyBind.optionBinder(binder, Key.get(DruidNodeAnnouncer.class))
            .addBinding(CONSUL_KEY)
            .to(ConsulDruidNodeAnnouncer.class)
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
}
