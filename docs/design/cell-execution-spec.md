---
id: cell-execution-spec
title: "Cell Execution Spec (CES)"
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

# Cell Execution Spec (CES)

This specification defines the minimum contract for cell-aware query execution.

## Scope

CES applies to:

- ingress routing
- broker planning/routing
- historical selection
- overlord task assignment
- MSQ controller and worker execution

## Canonical fields

| Field | Type | Required | Allowed values | Notes |
|---|---|---|---|---|
| `cell` | string | yes | deployment-specific | Execution cell. Example: `us-east-1a`. |
| `cellExecutionMode` | string | yes | `STRICT_CELL`, `CELL_FAILOVER` | Default is `STRICT_CELL`. |
| `allowRealtimeException` | boolean | yes | `true`, `false` | Allows realtime-only exception path. |
| `failoverReason` | string | conditional | free text | Required when `cellExecutionMode=CELL_FAILOVER`. |
| `failoverTicket` | string | conditional | change ticket id | Required when `cellExecutionMode=CELL_FAILOVER`. |

## Validation rules

1. `cell` MUST be present and non-empty.
2. `cellExecutionMode` MUST be one of the allowed values.
3. In `STRICT_CELL`, cross-cell execution MUST be rejected.
4. In `CELL_FAILOVER`, cross-cell execution MUST be auditable.
5. `allowRealtimeException` applies only to realtime paths and MUST NOT weaken strict historical/MSQ checks.

## Error taxonomy

| Error | Meaning | Retryable |
|---|---|---|
| `CellMissing` | No `cell` context provided. | no |
| `CellMismatch` | Runtime path diverged from declared `cell`. | no |
| `CellCoverageInsufficient` | Local cell lacks required replicas/capacity. | yes |
| `CellFailoverDenied` | Failover attempted without policy approval. | no |

## Audit requirements

In failover mode, logs must include:

- `cell`
- `cellExecutionMode`
- `failoverReason`
- `failoverTicket`
- actor identity
- toggle timestamp
- toggle expiry (TTL)

## Example query context

```json
{
  "cell": "us-east-1a",
  "cellExecutionMode": "STRICT_CELL",
  "allowRealtimeException": true
}
```

## Backward compatibility

If incoming requests do not provide CES fields, ingress or broker must normalize defaults before planning. Components must
not infer defaults independently.
