# Tasks: Client-server Query support (+ kvstore GET via Query)

**Input**: Design documents from `/specs/005-client-server-libraries/`
**Prerequisites**: plan.md (required), research.md, data-model.md, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extract: tech stack, libraries, structure
2. Load optional design documents:
   → data-model.md: Extract entities → model tasks
   → research.md: Extract decisions → setup/integration tasks
   → quickstart.md: Extract scenarios → integration tests
3. Generate tasks by category:
   → Setup: project checks
   → Tests: protocol/client/server/kvstore tests
   → Core: protocol + client + server + CLI changes
   → Integration: wiring, retry, leader routing
   → Polish: docs and benchmarks
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Same file = sequential
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001, T002...)
6. Create dependency notes and parallel examples
7. Return: SUCCESS (tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Phase 3.1: Setup
- [ ] T001 Verify multi-module build compiles and tests pass baseline
  - Command: `sbt clean test`
  - Notes: Establish baseline before changes

## Phase 3.2: Tests First (TDD)
- [X] T002 [P] Add protocol roundtrip tests for Query/QueryResponse codecs
  - Files: `client-server-protocol/src/main/scala/zio/raft/protocol/Codecs.scala`, tests in `client-server-protocol/src/test/scala/`
  - Scope: correlationId (opaque string), message frames
- [X] T003 [P] Add client unit tests for correlationId generation and preservation across retries
  - Files: `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`, `PendingQueries.scala`
  - Scope: ensure one completion per correlationId
- [X] T004 [P] Add server unit tests for handling Query and emitting QueryResponse (leader only)
  - Files: `client-server-server/src/main/scala/zio/raft/server/RaftServer.scala`
  - Scope: no state-machine invocation; leader-only constraint
- [X] T005 [P] Add kvstore integration test: GET uses Query and returns current value
  - Files: `kvstore-cli/src/main/scala/zio/kvstore/cli/KVClient.scala`, `kvstore/src/main/scala/zio/kvstore/KVServer.scala`
  - Scenario: set then get → value matches; GET does not mutate state
- [ ] T021 [P] Add client tests for PendingQueries resend triggers (mirror PendingRequests)
  - Files: client-server-client tests
  - Scenarios:
    - SessionCreated (new session) → resendAll for PendingQueries
    - SessionContinued (existing session) → resendAll for PendingQueries
    - Connected TimeoutCheck → resendExpired for PendingQueries (use requestTimeout)
    - Reconnect flows (NotLeaderAnymore, SessionError, ConnectionClosed) → upon SessionContinued, resendAll for PendingQueries
 - [ ] T023 [P] Add client test: out-of-order QueryResponse matching across retries/redirects
  - Files: client-server-client tests
  - Scenario: simulate multiple in-flight queries and delayed responses; verify correlationId-based matching delivers correct single completion

## Phase 3.3: Core Implementation
### Protocol
- [X] T006 Define Query and QueryResponse messages in protocol
  - Files: `client-server-protocol/src/main/scala/zio/raft/protocol/ClientMessages.scala`, `ServerMessages.scala`
  - Include: correlationId: String (opaque), payload for Query
- [X] T007 Implement codecs for Query/QueryResponse
  - Files: `client-server-protocol/src/main/scala/zio/raft/protocol/Codecs.scala`

### Client Library
- [X] T008 Add `query` API to `RaftClient`
  - File: `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`
  - Behavior: client-generated correlationId; same retry defaults as ClientRequest
- [X] T009 Implement `PendingQueries` to preserve correlationId across retries and ensure single completion
  - Files: `client-server-client/src/main/scala/zio/raft/client/PendingQueries.scala`
- [X] T010 Mirror resend logic for PendingQueries in `RaftClient`
  - Files: `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`, `PendingQueries.scala`
  - Implement: call `resendAll` on SessionCreated/SessionContinued; call `resendExpired` on TimeoutCheck; ensure reconnect flows (NotLeaderAnymore, SessionError, ConnectionClosed) lead to resendAll via SessionContinued
- [X] T024 Implement correlationId generation using ZIO Random (constitution IV)
  - Files: `client-server-client/src/main/scala/zio/raft/client/RaftClient.scala`
  - Notes: Inject Random for testability; avoid java.util.UUID

### Server Library
- [X] T011 Handle Query in `RaftServer` and produce `ServerAction.Query`
  - File: `client-server-server/src/main/scala/zio/raft/server/RaftServer.scala`
  - Constraint: leader-served, linearizable; bypass state machine
- [X] T022 Add API to send `QueryResponse` and send it over the wire
  - Files: `client-server-server/src/main/scala/zio/raft/server/RaftServer.scala`
  - Behavior: expose send method for `QueryResponse` and ensure it is delivered to the client

### kvstore Example
- [ ] T013 Replace GET command to use Query-based read in CLI
  - File: `kvstore-cli/src/main/scala/zio/kvstore/cli/KVClient.scala`
  - Behavior: issue Query with key, match response by correlationId
- [ ] T014 Implement server-side handler to answer GET Query from current in-memory state
  - File: `kvstore/src/main/scala/zio/kvstore/KVServer.scala`
  - Constraint: no state-machine invocation; read latest committed state

## Phase 3.4: Integration
- [ ] T015 Wire protocol types through client/server transports
  - Files: server transport files only
- [ ] T016 Align retry configuration and timeouts with ClientRequest defaults
  - Files: `client-server-client/src/main/scala/zio/raft/client/ClientConfig.scala`
- [ ] T017 Logging/metrics for Query path (request/response, retries)
  - Files: client/server modules; use existing logging facilities

## Phase 3.5: Polish
- [ ] T018 [P] Documentation: update quickstart to reflect GET via Query
  - File: `specs/005-client-server-libraries/quickstart.md`
- [ ] T019 [P] README/docs updates describing Query semantics
  - Files: `specs/005-client-server-libraries/spec.md`, repo README if needed
- [ ] T020 [P] Performance sanity check for Query path vs prior GET
  - Files: add simple ZIO Test benchmark in appropriate module

## Dependencies
- T002–T005 before T006–T014 (TDD)
- T006 before T007
 - T007 before T008–T011
 - T008–T011 before T013–T015
- T013 before T015 (end-to-end path)
- T016 after T008
- T021 before T010 (tests before resend logic implementation)
- T022 after T011 and before T013
 - T023 before T008 (test before client API impl)
 - T003 before T024 (test before Random-based generator impl)

## Parallel Execution Examples
```
# Run protocol/client/server tests in parallel
T002 [P], T003 [P], T004 [P]

# After codecs exist, parallelize client/server implementations where files differ
T008 [P], T011 [P]

# Docs and perf checks in parallel
T018 [P], T019 [P], T020 [P]
```

## Validation Checklist
- [ ] All new Query features have ZIO Test specs
- [ ] Query never invokes the state machine
- [ ] Leader-served only; connection model unchanged
- [ ] correlationId generated by client and preserved across retries
- [ ] Retry/payload configs identical to ClientRequest
- [ ] kvstore GET replaced by Query-based GET
