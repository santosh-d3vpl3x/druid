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

package org.apache.druid.server;

import com.google.common.base.Strings;
import org.apache.druid.query.QueryContexts;

import java.util.Map;

/**
 * Broker/Router-side context resolver that maps ingress identity signals to a canonical query cell.
 */
public class IngressIdentityCellResolver
{
  public static final String INGRESS_HOST_KEY = "ingressHost";
  public static final String INGRESS_AZ_KEY = "ingressAvailabilityZone";
  public static final String INGRESS_HEADER_KEY = "ingressCellHeader";
  public static final String INGRESS_DEFAULT_CELL_KEY = "ingressDefaultCell";

  private static final String DEFAULT_CELL = "default";

  public String resolve(final Map<String, Object> context)
  {
    final String fromHeader = QueryContexts.parseString(context, INGRESS_HEADER_KEY, null);
    if (!Strings.isNullOrEmpty(fromHeader)) {
      return fromHeader;
    }

    final String fromAz = QueryContexts.parseString(context, INGRESS_AZ_KEY, null);
    if (!Strings.isNullOrEmpty(fromAz)) {
      return fromAz;
    }

    final String fromHost = QueryContexts.parseString(context, INGRESS_HOST_KEY, null);
    if (!Strings.isNullOrEmpty(fromHost)) {
      return fromHost;
    }

    return QueryContexts.parseString(context, INGRESS_DEFAULT_CELL_KEY, DEFAULT_CELL);
  }
}
