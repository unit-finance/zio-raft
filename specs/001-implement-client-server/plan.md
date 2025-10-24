# Implementation Plan: Client-Server Communication for Raft Protocol

**Branch**: `001-implement-client-server` | **Date**: 2025-09-24 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-implement-client-server/spec.md`

## Execution Flow (/plan command scope)
```
1. Load feature spec from Input path
   → If not found: ERROR "No feature spec at {path}"
2. Fill Technical Context (scan for NEEDS CLARIFICATION)
   → Detect Project Type from context (web=frontend+backend, mobile=app+api)
   → Set Structure Decision based on project type
3. Fill the Constitution Check section based on the content of the constitution document.
4. Evaluate Constitution Check section below
   → If violations exist: Document in Complexity Tracking
   → If no justification possible: ERROR "Simplify approach first"
   → Update Progress Tracking: Initial Constitution Check
5. Execute Phase 0 → research.md
   → If NEEDS CLARIFICATION remain: ERROR "Resolve unknowns"
6. Execute Phase 1 → contracts, data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, `GEMINI.md` for Gemini CLI, `QWEN.md` for Qwen Code or `AGENTS.md` for opencode).
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

**IMPORTANT**: The /plan command STOPS at step 7. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

## Summary
Implement comprehensive client-server communication for ZIO Raft that enables reliable bidirectional messaging, durable session management, and strong consistency guarantees. The solution creates three new libraries (client, server, protocol) using ZeroMQ CLIENT/SERVER pattern for transport and scodec for protocol serialization. The server implements leader-aware operation validation with a ZStream-based action forwarding architecture to the Raft state machine, providing automatic reconnection and load balancing capabilities.

**Scala Version Support**:
- **Client Library**: Scala 2.13 + Scala 3 (cross-compiled for broad compatibility)
- **Protocol Library**: Scala 2.13 + Scala 3 (cross-compiled for shared usage)
- **Server Library**: Scala 3 only (leverages latest language features)

**Rationale**: Client and protocol libraries need wide compatibility for adoption by existing applications that may still use Scala 2.13. The server is internal to the Raft cluster and can leverage Scala 3's advanced features like union types, improved enums, and better type inference.

## Technical Context
**Language/Version**: 
- **Server**: Scala 3.3+ with ZIO 2.1+ (latest features for implementation)
- **Client/Protocol**: Scala 2.13.8+ and Scala 3.3+ with ZIO 2.1+ (cross-compiled for compatibility)

**Primary Dependencies**: ZeroMQ (zio-zmq), scodec (protocol serialization), ZIO (effects), ZIO Streams (action forwarding), ZIO Test (testing)  
**Storage**: Session state replicated via Raft consensus (no external storage)  
**Testing**: ZIO Test with property-based testing for distributed system scenarios  
**Target Platform**: JVM (Linux, macOS, Windows)
**Project Type**: single - creates new libraries within existing ZIO Raft project  
**Performance Goals**: Sub-50ms latency for local operations, handle 1000+ concurrent client sessions  
**Constraints**: Must preserve existing Raft abstractions, maintain backward compatibility, ensure linearizability  
**Scale/Scope**: 3 new libraries (client, server, protocol), ~5K LOC, comprehensive test coverage

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Functional Purity & Type Safety
- [x] All new code uses immutable data structures and ZIO effect types
- [x] No unsafe operations (casting, reflection) introduced
- [x] Type safety preserved throughout implementation

### II. Explicit Error Handling
- [x] All external interactions have explicit error handling
- [x] Business logic errors use ZIO.fail or Either types, not exceptions
- [x] Timeout and resource failures properly modeled

### III. Existing Code Preservation (NON-NEGOTIABLE)
- [x] Core interfaces (StateMachine, RPC, LogStore) not modified without architectural review
- [x] Backward compatibility maintained for public APIs
- [x] No performance degradation without measurement and justification

### IV. ZIO Ecosystem Consistency
- [x] ZIO primitives used for all concurrent operations
- [x] ZStream used for streaming, no external streaming libraries
- [x] Resource management follows ZIO Scope patterns

### V. Test-Driven Maintenance
- [x] Bug fixes include reproducing test cases
- [x] Performance changes include benchmark tests
- [x] Complex Raft scenarios have property-based tests

## Project Structure

### Documentation (this feature)
```
specs/001-implement-client-server/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
# New libraries to be created
client-server-protocol/         # 📍 Scala 2.13 + Scala 3 (cross-compiled)
├── src/main/scala/zio/raft/protocol/
│   ├── Messages.scala           # Protocol message definitions
│   ├── Codecs.scala            # scodec serialization
│   ├── package.scala           # Common types and newtypes (moved from Types.scala for Scala 2.13 compatibility)
│   └── package.scala           # Protocol utilities
└── src/test/scala/zio/raft/protocol/
    └── CodecSpec.scala         # Protocol serialization tests

client-server-server/           # 📍 Scala 3 only
├── src/main/scala/zio/raft/server/
│   ├── RaftServer.scala        # Main server implementation with leader awareness
│   ├── SessionManager.scala    # Session lifecycle management
│   ├── ClientHandler.scala     # Individual client connection handling
│   ├── ActionStream.scala      # ZStream-based action forwarding to Raft
│   └── ServerConfig.scala      # Configuration
└── src/test/scala/zio/raft/server/
    ├── ServerSpec.scala        # Server behavior tests
    └── SessionSpec.scala       # Session management tests

client-server-client/           # 📍 Scala 2.13 + Scala 3 (cross-compiled)
├── src/main/scala/zio/raft/client/
│   ├── RaftClient.scala        # Main client implementation & unified stream processing
│   ├── SessionState.scala      # Client session state management
│   ├── ConnectionManager.scala # Client connection state & request queuing  
│   ├── ActionStream.scala      # Client-side unified action stream processing
│   ├── RetryManager.scala      # Client retry logic
│   └── ClientConfig.scala      # Configuration
└── src/test/scala/zio/raft/client/
    ├── ClientSpec.scala        # Client behavior tests
    └── RetrySpec.scala         # Retry logic tests

# Integration tests
tests/integration/
└── ClientServerSpec.scala     # End-to-end integration tests
```

**Structure Decision**: Option 1 (Single project) with new library modules following SBT multi-project structure

## Phase 0: Outline & Research

### Research Topics Identified
1. **ZeroMQ Integration Patterns**: Best practices for ZIO + ZeroMQ integration, socket management, error handling, and leader-aware operation filtering
2. **scodec Protocol Design**: Effective patterns for versioned binary protocols, backwards compatibility strategies
3. **Session Management in Distributed Systems**: Proven patterns for durable sessions, timeout handling, and state replication
4. **Client Retry Strategies**: Exponential backoff, jitter, and idempotency patterns for distributed systems
5. **ZIO Resource Management**: Proper use of ZIO Scope, Resource, and cleanup patterns for network resources

### Research Tasks
- **Task R1**: Research ZeroMQ socket patterns (CLIENT/SERVER) for reliable bidirectional communication
- **Task R2**: Analyze maitred protocol patterns for scodec usage, versioning, and message discrimination
- **Task R3**: Study distributed session management patterns and state replication approaches
- **Task R4**: Research client retry patterns with exponential backoff and circuit breaker patterns
- **Task R5**: Investigate ZIO resource management best practices for network connections

**Output**: research.md with all technical decisions documented

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

### Entity Extraction from Specification
Key entities identified for data model:
- **Client Session**: Session ID, capabilities, connection state, heartbeat tracking
- **Session State**: Replicated session metadata across cluster
- **Protocol Messages**: Client/Server message hierarchies with scodec codecs
- **Request/Response Correlation**: Request IDs, timeouts, retry state
- **Keep-Alive Protocol**: Heartbeat messages and timeout detection

### API Contract Generation
Based on functional requirements, generate contracts for:
- **Session Management API**: Create/continue/terminate session operations
- **Command Submission API**: Client command submission with leader redirection
- **Server-Initiated Requests API**: One-way work dispatch from server to client with acknowledgment
- **Keep-Alive Protocol**: Heartbeat message exchange patterns
- **Error Response API**: Standardized error codes and leader information

### Contract Test Generation
Create failing contract tests for:
- Session lifecycle operations (create, continue, expire)
- Command submission and response patterns
- Server-initiated request delivery and acknowledgment cycles
- Keep-alive timeout detection
- Leader redirection scenarios

### Integration Test Scenarios
Extract test scenarios from user stories:
- Multi-client session management during leader changes
- Client retry behavior during network partitions
- Server-initiated work distribution with client failures
- Session durability across cluster node failures

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, CLAUDE.md

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (contracts, data model, quickstart)
- **Protocol Library**: Message definitions, scodec codecs, type definitions [P]
- **Server Library**: Session management, client handling, ZeroMQ integration [P]
- **Client Library**: Session state, retry logic, connection management [P]
- **Integration Tests**: End-to-end scenarios, failure injection tests
- **Performance Tests**: Latency benchmarks, concurrent session handling

**Ordering Strategy**:
- TDD order: Protocol tests → Protocol implementation → Server tests → Server implementation → Client tests → Client implementation
- Dependency order: Protocol → Server → Client → Integration tests
- Mark [P] for parallel execution within each library (independent files)

**Estimated Output**: 35-40 numbered, ordered tasks in tasks.md covering:
- Protocol library (8-10 tasks)
- Server library (12-15 tasks)  
- Client library (12-15 tasks)
- Integration and performance tests (5-8 tasks)

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*No constitutional violations identified - all requirements align with ZIO Raft principles*

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [x] Complexity deviations documented

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
