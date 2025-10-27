# Tasks: Add client-server to kvstore, CLI, and watch command

**Input**: Design documents from `/specs/004-add-client-server/`
**Prerequisites**: plan.md (required), research.md, data-model.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
2. Load data-model.md and research.md
3. Generate ordered tasks per template (TDD: tests before implementation)
4. Mark [P] for tasks in different files that can run in parallel
5. Output tasks.md in feature directory
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Phase 3.1: Setup
- [X] T001 Update `/Users/somdoron/git/zio-raft/build.sbt` to add subproject `kvstore-cli` depending on `client-server-client` and `kvstore`; ensure `kvstore` depends on `clientServerServer` as needed. Enable Scala 3.3+ and ZIO 2.1+.
- [X] T002 [P] Remove stale HTTP server comment from `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/App.scala`; ensure example no longer references HTTP anywhere.
- [X] T003 [P] Extract `KVStateMachine` from `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/App.scala` to `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/KVStateMachine.scala`; create package object at `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/package.scala` and move remaining types (`KVCommand`, `KVResponse`, `SetDone`, `GetResult`, `KVKey`, type aliases/codecs) into the package object.

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
- [ ] T004 [P] Create `Watch returns current value and updates` integration test at `/Users/somdoron/git/zio-raft/kvstore/src/test/scala/zio/kvstore/WatchSpec.scala` that: starts server, uses client to `set k1 v1`, `watch k1`, asserts initial event v1 then after `set k1 v2` receives v2. Use ZIO Test `suiteAll` and `assertTrue`.
- [ ] T005 [P] Create `Duplicate watch is idempotent` test in `/Users/somdoron/git/zio-raft/kvstore/src/test/scala/zio/kvstore/WatchSpec.scala` asserting second `watch k1` by same session has no effect (no duplicate events/registrations). Use `suiteAll` and `assertTrue`.
- [ ] T006 [P] Create `Session expiry removes subscriptions` test in `/Users/somdoron/git/zio-raft/kvstore/src/test/scala/zio/kvstore/SessionExpirySpec.scala` asserting after expire-session, no further watch notifications delivered. Use `suiteAll` and `assertTrue`.
- [ ] T007 [P] Create `All updates delivered, no coalescing` test in `/Users/somdoron/git/zio-raft/kvstore/src/test/scala/zio/kvstore/DeliverySpec.scala` asserting multiple rapid sets emit all events to watcher (ordering across sessions not asserted). Use `suiteAll` and `assertTrue`.

-## Phase 3.3: Core Implementation (ONLY after tests are failing)
- [X] T008 Define `KVServerRequest` ADT at `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/protocol/KVServerRequest.scala` with `Notification(key: String, value: String)`; update usages to replace previous `NoServerRequest` alias.
- [X] T009 Add `KVCommand.Watch(key: String)` in `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/package.scala`; define watch semantics including initial snapshot (return current value immediately) and idempotency (duplicate watch has no effect).
- [X] T010 Update `KVStateMachine` at `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/KVStateMachine.scala` to manage subscriptions in `HMap` per spec: add schema entries for (a) key → Set[SessionId], (b) sessionId → Set[key]; register on watch; remove in `handleSessionExpired`; in `applyCommand(Set)`, after updating state, fan out `KVServerRequest.Notification` to all sessions subscribed to that key.
- [X] T011 Update scodec codecs in `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/package.scala` and `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/protocol/KVServerRequest.scala` to cover new command/response/server-request types.
- [ ] T012 Create `Node` skeleton at `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/node/Node.scala` with constructor wiring (server, raft core, state machine) and `run` method signature; no logic yet.
- [X] T012 Create `Node` skeleton at `/Users/somdoron/git/zio-raft/kvstore/src/main/scala/zio/kvstore/node/Node.scala` with constructor wiring (server, raft core, state machine) and `run` method signature; no logic yet.
- [X] T013 Implement `Node` raft actions processing stream: consume `RaftServer.raftActions`, map to `SessionCommand`, call state machine, and based on results publish via `RaftServer.sendClientResponse`, `sendServerRequest`, `sendRequestError`, or `confirmSessionCreation`.
- [X] T014 Implement `Node` 10s retry stream: periodically check `hasPendingRequests` (dirty read) and when true initiate `SessionCommand.GetRequestsForRetry`; publish resulting actions via the same `RaftServer` methods.
- [X] T015 Implement `Node` raft state notifications stream: consume Raft core `stateNotifications` and map to `RaftServer.stepUp`/`stepDown` to reflect leadership changes.
- [X] T016 Compose `Node.run` to run raftActions stream, retry stream, and state notifications stream concurrently; ensure backpressure/termination semantics; publish all outputs via appropriate `RaftServer` methods.
- [ ] T017 Create `kvstore-cli` skeleton at `/Users/somdoron/git/zio-raft/kvstore-cli/src/main/scala/zio/kvstore/cli/Main.scala`; initialize client connection to Raft cluster using `client-server-client`. Read endpoints from `--endpoints` flag or `KVSTORE_ENDPOINTS`; default to localhost if unspecified.
- [ ] T018 Implement `set` command in CLI: parse args and send set request via client; print confirmation.
- [ ] T019 Implement `get` command in CLI: parse args and fetch value; print key/value.
- [ ] T020 Implement `watch` command in CLI: parse args, subscribe to key; print initial value and stream subsequent updates until session ends.

## Phase 3.4: Integration
- [ ] T021 Wire `KVStoreServerApp` to start the Raft server and run `Node` (raftActions, retry, and state notifications streams) and expose graceful shutdown.
- [ ] T022 Ensure subscriptions are removed on session expiry by updating `handleSessionExpired` in `KVStateMachine` and validating via logs/tests.
- [ ] T023 Add minimal logging and metrics using ZIO logging for watch and Node retry/state paths.

## Phase 3.5: Polish
- [ ] T024 [P] Update `/Users/somdoron/git/zio-raft/specs/004-add-client-server/quickstart.md` with exact sbt module names and run commands for server and CLI.
- [ ] T025 [P] Update `/Users/somdoron/git/zio-raft/README.md` to reference the new example and CLI.
- [ ] T026 [P] Unit tests for codec roundtrips for `KVServerRequest.Notification` and `KVCommand.Watch` in `/Users/somdoron/git/zio-raft/kvstore/src/test/scala/zio/kvstore/CodecSpec.scala`.
- [ ] T027 Code review: ensure ZIO ecosystem consistency and constitution compliance; remove any lingering HTTP comments.

## Dependencies
- Setup (T001-T003) before tests and implementation
- Tests (T004-T007) before core implementation (T008-T020)
- T008 blocks T009-T011
- T012-T016 block T021
- Implementation before polish (T024-T027)

## Parallel Example
```
# Launch independent tests in parallel:
Task: "T004 Watch returns current value and updates"
Task: "T005 Duplicate watch is idempotent"
Task: "T006 Session expiry removes subscriptions"
Task: "T007 All updates delivered, no coalescing"

# Launch independent polish tasks in parallel:
Task: "T024 Update quickstart.md"
Task: "T025 Update README.md"
Task: "T026 Codec unit tests"
```

## Validation Checklist
- [ ] All functionality has corresponding ZIO Test specifications
- [ ] All data models use immutable structures and type safety
- [ ] All tests come before implementation (TDD compliance)
- [ ] Parallel tasks truly independent and don't affect shared state
- [ ] Each task preserves existing abstractions and APIs
- [ ] No task introduces unsafe operations or exceptions for business logic
- [ ] ZIO ecosystem consistency maintained throughout
