---
id: cell-coverage-routing-rollout
title: "Cell coverage and strict routing rollout"
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

# Cell coverage and strict routing rollout plan

This document defines a phased rollout for cell-aware query execution where each Availability Zone (AZ) is treated as
an execution cell. In strict mode, queries execute end-to-end within their ingress cell for Broker routing, Historical
reads, and MSQ controller/worker/shuffle paths. The only exception is realtime indexing and realtime reads.

Related documents:

- [`cell-execution-spec.md`](./cell-execution-spec.md)
- [`../operations/cell-rollout-gate-checklist.md`](../operations/cell-rollout-gate-checklist.md)

## Policy model

- **STRICT_CELL**: no cross-cell reads or shuffle; fail or queue when local cell cannot satisfy execution.
- **CELL_FAILOVER**: controlled cross-cell fallback when an explicit failover mode is enabled and audited with
  `failoverReason` and `failoverTicket`.

## Cell Execution Spec (CES) minimum contract

Every component participating in query execution must implement the same CES surface.

### Required context keys

| Key | Type | Required | Description |
|---|---|---|---|
| `cell` | string | yes | Execution cell identifier, typically derived from ingress AZ. |
| `cellExecutionMode` | enum | yes | `STRICT_CELL` or `CELL_FAILOVER`. |
| `allowRealtimeException` | boolean | yes | Whether realtime exception path is permitted for this request. |
| `failoverReason` | string | no | Required in failover mode for auditability. |
| `failoverTicket` | string | no | Required in failover mode for audit linkage. |

### Required error classes

- `CellMissing`: request did not carry a valid cell identifier.
- `CellMismatch`: component observed an execution path that diverged from declared cell.
- `CellCoverageInsufficient`: local cell lacks segment/task capacity required for strict execution.
- `CellFailoverDenied`: failover requested but policy or guardrail disallows it.

### Required telemetry dimensions

All query/task logs and metrics must include:

- `cell`
- `ingressCell`
- `executionCell`
- `cellExecutionMode`
- `crossCell`
- `sourceCell` and `targetCell` for networked hops
- `failoverReason` when execution mode is `CELL_FAILOVER`

## Rollout phases

### Phase 0 — Observability + Cell Execution Spec (CES)

**Goal**
- Create a single execution contract and prove that every component emits cell dimensions.

**Required outputs**
- CES document with normative rules for `cell`, strict/failover modes, error semantics, and the realtime exception.
- Standard dimensions added to logs and metrics: `cell`, `ingressCell`, `executionCell`, `crossCell`,
  `fallbackMode`, and source/target cell fields for networked paths.
- Dashboard showing cross-cell bytes, query-level cross-cell attempts, and MSQ assignment locality.

**Exit criteria**
- All components emit required dimensions in one environment.
- CES is reviewed and marked as canonical.
- No strict behavior changes are enabled yet.

**Hard gate checks**
- One dashboard link attached with 24h evidence.
- One log sample per component proving CES fields are present.
- Error mapping table published for CES error classes.

### Phase 1 — Historical coverage enforcement

**Goal**
- Ensure each required datasource has complete in-cell segment coverage so strict routing can succeed.

**Required outputs**
- Automated coverage checker that validates local-cell segment availability against policy.
- Tier/rule configuration aligned to per-cell coverage targets.
- Alerting for missing local replicas and load queue lag by cell.

**Exit criteria**
- Coverage checker is green for all protected datasources.
- Coverage drift alerts are active.
- Documented remediation playbook exists for replica gaps.

**Hard gate checks**
- Coverage checker runs automatically at least hourly.
- Any red coverage condition pages on-call.
- Replica gap remediation runbook tested once in non-prod.

### Phase 2 — Broker strict routing in shadow mode

**Goal**
- Enable strict in-cell Broker server selection in shadow mode to measure impact before enforcement.

**Required outputs**
- Broker routing filter by `query.context.cell` with strict/failover flag support.
- Shadow-mode validation that records what strict routing would have selected.
- Error classification for `CellMissing`, `CellMismatch`, and `CellCoverageInsufficient`.

**Exit criteria**
- Shadow mode runs for at least 72 hours without unresolved blockers.
- Measured strict selections match intended local-cell candidates.
- No unexplained routing mismatches.

**Hard gate checks**
- Shadow/strict decision delta report generated every 6 hours.
- Fewer than 0.1% unresolved routing mismatches during observation window.
- Rollback switch validated in staging.

### Phase 3 — MSQ cell pinning and shuffle guardrails

**Goal**
- Constrain MSQ `query_controller` and `query_worker` tasks to same cell and prevent cross-cell shuffle in strict mode.

**Required outputs**
- Worker cell labels and scheduler constraints using task context `cell`.
- Guardrails in controller planning and/or stage launch that reject cross-cell worker sets in strict mode.
- Counters for denied cross-cell assignment and shuffle attempts.

**Exit criteria**
- MSQ tasks in strict mode are cell-local in integration tests and shadow environment.
- Cross-cell shuffle counters are zero in strict mode.
- Capacity guidance published for avoiding strict-mode pending spikes.

**Hard gate checks**
- `query_controller` and `query_worker` placement reports attached.
- Cross-cell shuffle counter verified zero for 72h in shadow.
- Strict-mode pending threshold alert configured and tested.

### Phase 4 — Strict default with controlled failover

**Goal**
- Make strict cell execution the default production mode with explicit, auditable failover toggles.

**Required outputs**
- Strict mode defaulted at ingress/broker and task submission boundaries.
- Failover mode switch with TTL, audit logging, and runbook requirements.
- Final production SLOs and alert thresholds.

**Exit criteria**
- Strict mode production rollout complete.
- At least one failover game-day drill completed and signed off.
- Regression gates in CI prevent contract drift.

**Hard gate checks**
- Failover enable/disable is audited with operator identity and reason.
- Automatic expiry (TTL) exists for failover mode toggles.
- CI block is enabled on all cell-sensitive paths.

## Required game-day scenarios at every phase gate

Execute the following scenarios before promoting phases:

1. **Metadata gap drill**: missing cell metadata on request/task and validation behavior.
2. **Partial cell outage drill**: one Historical subset unavailable, validate strict failures and failover behavior.
3. **Broker toggle/restart drill**: strict/failover switch persistence, restart safety, and config drift checks.
4. **Rebalance under load drill**: tier movement while sustaining query load and observing coverage alerts.
5. **MSQ mixed-label shuffle drill**: attempt mixed-cell worker assignment and verify strict rejection.
6. **Failover rollback drill**: enable failover, recover local cell, then return to strict with zero stale toggles.

## Promotion checklist template

Use this template before phase advancement:

- [ ] Phase objective and scope documented.
- [ ] CES compatibility confirmed (no interface drift).
- [ ] Unit, contract, and integration tests passed.
- [ ] Observability dashboard snapshots attached (last 24h / 72h).
- [ ] Game-day scenario evidence attached.
- [ ] On-call runbook updated.
- [ ] Rollback steps tested and recorded.
- [ ] Change advisory and stakeholder approvals captured.

## Component mapping (who must implement what)

| Component | Required changes | Verification artifact |
|---|---|---|
| Ingress/Router | Set `cell` and `cellExecutionMode` defaults; reject malformed context. | Request logs showing normalized context. |
| Broker | Enforce strict local candidate selection; emit CES errors and dimensions. | Shadow delta reports + strict routing audit. |
| Historical | Advertise cell identity; support cell-aware selection visibility. | Service inventory with cell labels. |
| Overlord/Task scheduler | Enforce MSQ task placement by `cell` in strict mode. | Task placement report by task type and cell. |
| MSQ controller | Reject mixed-cell worker sets in strict mode. | Stage-level assignment validation logs. |
| MSQ workers | Emit source/target cell for shuffle hops. | Shuffle telemetry with zero cross-cell in strict. |
| SRE/Operations | Own failover controls, TTL, and audit process. | Runbook and change log evidence. |

## Release gating in CI/CD

For any code path that can affect routing, scheduling, or MSQ shuffle:

1. Contract tests for CES keys and errors.
2. Integration test proving strict local execution (except realtime exception path).
3. Regression test proving failover behavior is controlled and auditable.
4. Lint/check requiring docs updates when CES changes.

Changes must not be promoted if any of the above checks fail.

## Ownership and governance

- Keep one control board with gate states: `Spec Ready`, `Build`, `Contract Tests`, `Integration Tests`, `Shadow`,
  `Prod`.
- Block merges to cell-sensitive components unless CES section references and gate evidence are included.
- Maintain release criteria as hard gates; do not bypass game-day evidence.
