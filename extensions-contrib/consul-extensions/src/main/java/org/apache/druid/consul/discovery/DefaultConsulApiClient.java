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
import com.ecwid.consul.v1.agent.model.NewService;
import com.ecwid.consul.v1.health.model.HealthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default implementation of ConsulApiClient using the Ecwid Consul client library.
 */
public class DefaultConsulApiClient implements ConsulApiClient
{
  private static final Logger LOGGER = new Logger(DefaultConsulApiClient.class);

  private final ConsulClient consulClient;
  private final ConsulDiscoveryConfig config;
  private final ObjectMapper jsonMapper;

  public DefaultConsulApiClient(
      ConsulDiscoveryConfig config,
      ObjectMapper jsonMapper
  )
  {
    this.config = Preconditions.checkNotNull(config, "config");
    this.jsonMapper = Preconditions.checkNotNull(jsonMapper, "jsonMapper");

    if (config.getAclToken() != null) {
      this.consulClient = new ConsulClient(config.getHost(), config.getPort(), config.getAclToken());
    } else {
      this.consulClient = new ConsulClient(config.getHost(), config.getPort());
    }

    LOGGER.info(
        "Created Consul client for [%s:%d] with service prefix [%s]",
        config.getHost(),
        config.getPort(),
        config.getServicePrefix()
    );
  }

  @Override
  public void registerService(DiscoveryDruidNode node) throws Exception
  {
    String serviceId = makeServiceId(node);
    String serviceName = makeServiceName(node.getNodeRole());

    NewService service = new NewService();
    service.setId(serviceId);
    service.setName(serviceName);
    service.setAddress(node.getDruidNode().getHost());
    service.setPort(node.getDruidNode().getPlaintextPort());

    // Add tags
    List<String> tags = new ArrayList<>();
    tags.add("druid");
    tags.add("role:" + node.getNodeRole().getJsonName());
    service.setTags(tags);

    // Serialize the full DiscoveryDruidNode as metadata
    String nodeJson = jsonMapper.writeValueAsString(node);
    service.setMeta(Collections.singletonMap("druid_node", nodeJson));

    // Configure TTL health check
    NewService.Check check = new NewService.Check();
    check.setTtl(StringUtils.format("%ds", config.getHealthCheckInterval().getStandardSeconds()));
    check.setDeregisterCriticalServiceAfter(
        StringUtils.format("%ds", config.getDeregisterAfter().getStandardSeconds())
    );
    service.setCheck(check);

    consulClient.agentServiceRegister(service, config.getDatacenter());

    // Immediately mark as passing
    consulClient.agentCheckPass("service:" + serviceId, "Druid node is healthy");

    LOGGER.info("Registered service [%s] with Consul: %s", serviceId, node);
  }

  @Override
  public void deregisterService(String serviceId) throws Exception
  {
    consulClient.agentServiceDeregister(serviceId);
    LOGGER.info("Deregistered service [%s] from Consul", serviceId);
  }

  @Override
  public List<DiscoveryDruidNode> getHealthyServices(NodeRole nodeRole) throws Exception
  {
    String serviceName = makeServiceName(nodeRole);
    Response<List<HealthService>> response = consulClient.getHealthServices(
        serviceName,
        true, // only healthy
        QueryParams.DEFAULT,
        config.getDatacenter()
    );

    return parseHealthServices(response.getValue());
  }

  @Override
  public ConsulWatchResult watchServices(NodeRole nodeRole, long lastIndex, long waitSeconds) throws Exception
  {
    String serviceName = makeServiceName(nodeRole);

    QueryParams queryParams = new QueryParams(waitSeconds, lastIndex);
    Response<List<HealthService>> response = consulClient.getHealthServices(
        serviceName,
        true, // only healthy
        queryParams,
        config.getDatacenter()
    );

    List<DiscoveryDruidNode> nodes = parseHealthServices(response.getValue());
    long newIndex = response.getConsulIndex() != null ? response.getConsulIndex() : lastIndex;

    return new ConsulWatchResult(nodes, newIndex);
  }

  @Override
  public void close()
  {
    // Consul client doesn't need explicit cleanup
    LOGGER.info("Closed Consul client");
  }

  /**
   * Get the underlying Consul client for direct access (e.g., for health checks).
   * Package-private for use by ConsulDruidNodeAnnouncer.
   */
  ConsulClient getConsulClient()
  {
    return consulClient;
  }

  private String makeServiceName(NodeRole nodeRole)
  {
    return config.getServicePrefix() + "-" + nodeRole.getJsonName();
  }

  private String makeServiceId(DiscoveryDruidNode node)
  {
    return makeServiceName(node.getNodeRole()) + "-" +
           node.getDruidNode().getHost() + "-" +
           node.getDruidNode().getPlaintextPort();
  }

  private List<DiscoveryDruidNode> parseHealthServices(List<HealthService> healthServices)
  {
    if (healthServices == null || healthServices.isEmpty()) {
      return Collections.emptyList();
    }

    List<DiscoveryDruidNode> nodes = new ArrayList<>();
    for (HealthService healthService : healthServices) {
      try {
        if (healthService.getService() != null &&
            healthService.getService().getMeta() != null &&
            healthService.getService().getMeta().containsKey("druid_node")) {

          String nodeJson = healthService.getService().getMeta().get("druid_node");
          DiscoveryDruidNode node = jsonMapper.readValue(nodeJson, DiscoveryDruidNode.class);
          nodes.add(node);
        }
      }
      catch (IOException e) {
        LOGGER.error(e, "Failed to parse DiscoveryDruidNode from Consul service metadata");
      }
    }

    return nodes;
  }
}
