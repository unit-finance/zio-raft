# Tasks: Client-Server Communication for Raft Protocol

**Input**: Design documents from `/specs/001-implement-client-server/`  
**Prerequisites**: plan.md, research.md, data-model.md, contracts/, quickstart.md

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → Extract: ZIO 2.1+, ZeroMQ (CLIENT/SERVER), scodec, 3 libraries structure
2. Load design documents:
   → data-model.md: Protocol messages, connection states, stream architecture
   → contracts/: 4 protocol contracts (session, command, keep-alive, server-requests)
   → quickstart.md: 8 test scenarios for integration validation
3. Generate tasks by category following TDD approach
4. Apply parallel execution for independent files/modules
5. Validate all contracts have tests and entities have implementations
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- File paths are relative to repository root

## Phase 3.1: Setup

- [x] T001 Create multi-module SBT project structure (client-server-protocol/, client-server-server/, client-server-client/, tests/integration/)
- [x] T002 Configure build.sbt with Scala 3.3+/2.13 cross-compilation and ZIO 2.1+ dependencies 
- [x] T003 [P] Configure scalafmt, scalafix, and strict compilation flags in project/
- [x] T004 [P] Setup ZeroMQ (zio-zmq) and scodec dependencies in build.sbt
- [x] T005 [P] Initialize project structure and package objects for zio.raft.protocol, zio.raft.server, zio.raft.client

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3

**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**

### Protocol Contract Tests
- [x] T006 [P] Session management contract test in client-server-protocol/src/test/scala/zio/raft/protocol/SessionManagementSpec.scala
- [x] T007 [P] Command submission contract test in client-server-protocol/src/test/scala/zio/raft/protocol/CommandSubmissionSpec.scala  
- [x] T008 [P] Keep-alive protocol contract test in client-server-protocol/src/test/scala/zio/raft/protocol/KeepAliveSpec.scala
- [x] T009 [P] Server-initiated requests contract test in client-server-protocol/src/test/scala/zio/raft/protocol/ServerRequestsSpec.scala

### Core Component Tests
- [x] T010 [P] Protocol message codec tests in client-server-protocol/src/test/scala/zio/raft/protocol/CodecSpec.scala
- [x] T011 [P] Server session manager tests in client-server-server/src/test/scala/zio/raft/server/SessionManagerSpec.scala
- [x] T012 [P] Server action stream tests in client-server-server/src/test/scala/zio/raft/server/ActionStreamSpec.scala
- [x] T013 [P] Client connection manager tests in client-server-client/src/test/scala/zio/raft/client/ConnectionManagerSpec.scala
- [x] T014 [P] Client action stream tests in client-server-client/src/test/scala/zio/raft/client/ActionStreamSpec.scala
- [x] T015 [P] Client retry manager tests in client-server-client/src/test/scala/zio/raft/client/RetryManagerSpec.scala

### Integration Test Scenarios (from quickstart.md)
- [x] T016 [P] Session management integration test in tests/integration/SessionManagementIntegrationSpec.scala
- [x] T017 [P] Command submission integration test in tests/integration/CommandSubmissionIntegrationSpec.scala
- [x] T018 [P] Leadership handling integration test in tests/integration/LeadershipIntegrationSpec.scala
- [x] T019 [P] Session durability integration test in tests/integration/SessionDurabilityIntegrationSpec.scala
- [x] T020 [P] Server-initiated requests integration test in tests/integration/ServerRequestsIntegrationSpec.scala
- [x] T021 [P] Client connection state integration test in tests/integration/ClientConnectionStateIntegrationSpec.scala
- [x] T022 [P] Client stream architecture integration test in tests/integration/ClientStreamIntegrationSpec.scala
- [x] T023 [P] Session timeout cleanup integration test in tests/integration/SessionTimeoutIntegrationSpec.scala

## Phase 3.3: Core Implementation (ONLY after tests are failing)

### Protocol Library (client-server-protocol/)
- [x] T024 [P] Protocol message definitions in client-server-protocol/src/main/scala/zio/raft/protocol/Messages.scala
- [x] T025 [P] Common types and newtypes in client-server-protocol/src/main/scala/zio/raft/protocol/Types.scala
- [x] T026 [P] scodec serialization codecs in client-server-protocol/src/main/scala/zio/raft/protocol/Codecs.scala
- [x] T027 [P] Protocol utilities and package object in client-server-protocol/src/main/scala/zio/raft/protocol/package.scala

### Server Library (client-server-server/)
- [x] T028 Session manager implementation in client-server-server/src/main/scala/zio/raft/server/SessionManager.scala
- [x] T029 Action stream implementation in client-server-server/src/main/scala/zio/raft/server/ActionStream.scala
- [x] T030 Client handler implementation in client-server-server/src/main/scala/zio/raft/server/ClientHandler.scala
- [x] T031 Server configuration in client-server-server/src/main/scala/zio/raft/server/ServerConfig.scala
- [x] T032 Main RaftServer implementation in client-server-server/src/main/scala/zio/raft/server/RaftServer.scala

### Client Library (client-server-client/) 
- [x] T033 [P] Client connection manager in client-server-client/src/main/scala/zio/raft/client/ConnectionManager.scala
- [x] T034 [P] Client action stream processing in client-server-client/src/main/scala/zio/raft/client/ActionStream.scala
- [x] T035 [P] Session state management in client-server-client/src/main/scala/zio/raft/client/SessionState.scala
- [x] T036 [P] Client retry manager in client-server-client/src/main/scala/zio/raft/client/RetryManager.scala
- [x] T037 [P] Client configuration in client-server-client/src/main/scala/zio/raft/client/ClientConfig.scala
- [x] T038 Main RaftClient implementation in client-server-client/src/main/scala/zio/raft/client/RaftClient.scala

## Phase 3.4: Integration

- [x] T039 ZeroMQ transport integration for server in client-server-server/src/main/scala/zio/raft/server/ZmqTransport.scala
- [x] T040 ZeroMQ transport integration for client in client-server-client/src/main/scala/zio/raft/client/ZmqTransport.scala  
- [x] T041 Raft state machine integration for server actions in client-server-server/src/main/scala/zio/raft/server/RaftIntegration.scala
- [x] T042 Leader awareness and monitoring in client-server-server/src/main/scala/zio/raft/server/LeadershipMonitor.scala
- [x] T043 Error handling and timeout management across all libraries (ErrorHandling.scala)
- [x] T044 Resource management using ZIO Scope patterns for socket cleanup (ResourceManager.scala)
- [x] T045 Cross-compilation build validation for Scala 2.13 + 3 compatibility (protocol and client libraries validated)

## Phase 3.5: Polish

- [~] T046 [P] Unit tests for edge cases and error scenarios in all test suites (documented in IMPLEMENTATION_STATUS.md)
- [~] T047 [P] Property-based tests for protocol message round-trip validation (documented in IMPLEMENTATION_STATUS.md)
- [~] T048 [P] Performance benchmarks for session throughput and latency (documented in IMPLEMENTATION_STATUS.md)
- [~] T049 [P] Memory usage validation for concurrent session handling (documented in IMPLEMENTATION_STATUS.md)
- [~] T050 [P] Update API documentation and examples (in-code docs complete, external docs documented)
- [x] T051 Code review for ZIO ecosystem consistency and functional purity (completed - see IMPLEMENTATION_STATUS.md)
- [x] T052 Constitution compliance verification checklist (completed - see IMPLEMENTATION_STATUS.md)
- [x] T053 Integration with existing ZIO Raft codebase validation (completed - see IMPLEMENTATION_STATUS.md)

## Dependencies

### Critical Path Dependencies
- **Setup** (T001-T005) → **Tests** (T006-T023) → **Core** (T024-T038) → **Integration** (T039-T045) → **Polish** (T046-T053)
- **Protocol Tests** (T006-T009) → **Protocol Implementation** (T024-T027)
- **Component Tests** (T010-T015) → **Component Implementation** (T028-T038)
- **Integration Tests** (T016-T023) → **Integration Implementation** (T039-T045)

### Specific Dependencies
- T024-T027 (Protocol) must complete before T028-T038 (Server/Client implementations)
- T028 (SessionManager) must complete before T032 (RaftServer)
- T033-T037 (Client components) must complete before T038 (RaftClient)
- T039-T040 (ZMQ transports) depend on T024-T027 (Protocol)

## Parallel Example

### Tests Phase (Run in parallel after T005):
```bash
# Protocol contract tests - can run simultaneously
Task: "Session management contract test in client-server-protocol/src/test/scala/zio/raft/protocol/SessionManagementSpec.scala"
Task: "Command submission contract test in client-server-protocol/src/test/scala/zio/raft/protocol/CommandSubmissionSpec.scala"
Task: "Keep-alive protocol contract test in client-server-protocol/src/test/scala/zio/raft/protocol/KeepAliveSpec.scala"
Task: "Server-initiated requests contract test in client-server-protocol/src/test/scala/zio/raft/protocol/ServerRequestsSpec.scala"
```

### Core Implementation Phase (Run in parallel after tests fail):
```bash  
# Protocol library - independent files
Task: "Protocol message definitions in client-server-protocol/src/main/scala/zio/raft/protocol/Messages.scala"
Task: "Common types and newtypes in client-server-protocol/src/main/scala/zio/raft/protocol/Types.scala"
Task: "scodec serialization codecs in client-server-protocol/src/main/scala/zio/raft/protocol/Codecs.scala"

# Client library components - can be developed in parallel
Task: "Client connection manager in client-server-client/src/main/scala/zio/raft/client/ConnectionManager.scala"
Task: "Client action stream processing in client-server-client/src/main/scala/zio/raft/client/ActionStream.scala"
Task: "Session state management in client-server-client/src/main/scala/zio/raft/client/SessionState.scala"
```

## Validation Checklist
*Verified before task execution begins*

- [x] All 4 protocol contracts have corresponding contract tests (T006-T009)
- [x] All core entities from data-model.md have implementation tasks
- [x] All 8 integration scenarios from quickstart.md have test tasks (T016-T023)
- [x] TDD approach enforced: tests written before any implementation
- [x] Cross-compilation requirements addressed in setup and validation tasks
- [x] ZeroMQ CLIENT/SERVER pattern addressed in transport tasks
- [x] Stream architecture (both client and server) covered in action stream tasks
- [x] Session management durability requirements covered
- [x] All parallel tasks operate on different files with no shared state conflicts
- [x] ZIO ecosystem consistency maintained throughout all implementation tasks
- [x] Existing Raft abstractions preserved and extended rather than replaced

## Notes

- **Cross-Compilation**: Tasks T002, T045 specifically address Scala 2.13 + 3 compatibility for client and protocol libraries
- **Stream Architecture**: Both server (T012, T029) and client (T014, T034) have dedicated stream processing implementations
- **ZeroMQ Pattern**: Uses CLIENT/SERVER sockets as specified in research decisions, implemented in T039-T040
- **Session Durability**: Covered through session management (T028), leader monitoring (T042), and integration tests (T019)
- **Protocol Versioning**: scodec-based versioning handled in protocol codecs (T026)
- **Resource Management**: ZIO Scope patterns for socket cleanup addressed in T044
- **Performance**: Latency and throughput requirements validated in T048-T049
