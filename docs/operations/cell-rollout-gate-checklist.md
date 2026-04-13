---
id: cell-rollout-gate-checklist
title: "Cell rollout gate checklist"
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

# Cell rollout gate checklist

Use this checklist to promote a phase of strict cell routing.

## Gate metadata

- Phase:
- Environment:
- Date:
- Change owner:
- On-call approver:

## Required evidence

- [ ] CES contract version pinned in rollout ticket.
- [ ] 24h dashboard snapshot attached.
- [ ] 72h dashboard snapshot attached (for phases >= 2).
- [ ] Error budget impact report attached.
- [ ] Game-day evidence attached.
- [ ] Rollback drill evidence attached.

## Runtime checks

- [ ] `crossCell` is zero for strict-mode protected paths.
- [ ] Coverage checker is green for protected datasources.
- [ ] Failover toggles are disabled or have valid TTL + ticket.
- [ ] Alerts routed and acknowledged in test firing.

## Promotion decision

- [ ] Promote
- [ ] Hold
- [ ] Roll back

## Approvals

- Platform:
- SRE:
- Data infrastructure:
- Incident manager (if failover was used):
