
# Implementation Plan: Session State Machine Framework

**Branch**: `002-composable-raft-state` (unchanged) | **Date**: 2025-10-21 | **Spec**: [spec.md](./spec.md)
**Spec Directory**: `002-session-state-machine`  
**Input**: Feature specification from `/specs/002-session-state-machine/spec.md`

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

This feature implements a session state machine framework for ZIO Raft that provides linearizable semantics and exactly-once command execution. Based on Chapter 6.3 of the Raft dissertation, the SessionStateMachine wraps user-defined business logic state machines and automatically handles: idempotency checking, response caching, server-initiated request management, and session lifecycle event forwarding. 

**Core Capability**: Developers define custom state machines that process commands deterministically while the framework automatically handles:
- Idempotency checking via (sessionId, requestId) pairs
- Response caching for duplicate detection
- Server-initiated request tracking with cumulative acknowledgments
- State snapshotting using shared dictionary with key prefixes

**Architectural Approach**: The library provides state machine logic only (pure functions accepting RaftAction events and returning state transitions). Users are responsible for integration glue: wiring events from client-server library to state machine, sending responses via ServerAction events, and implementing retry processes with dirty reads for optimization.

## Technical Context
**Language/Version**: Scala 3.3+  
**Primary Dependencies**: ZIO 2.1+, scodec (for serialization), existing client-server-server library  
**Storage**: State maintained in Raft log + snapshots; uses shared Map[String, ByteVector] dictionary with key prefixes  
**Testing**: ZIO Test with `suiteAll` macro, property-based tests for state machine transitions  
**Target Platform**: JVM (cross-platform via Scala)  
**Project Type**: Single library module (`state-machine` or similar) integrated into existing ZIO Raft project  
**Performance Goals**: Deterministic O(1) idempotency checks; no size limits on response cache (for now); dirty reads for retry optimization  
**Constraints**: Must not modify client-server library interfaces; user state machines MUST NOT throw exceptions (errors in response payload); cumulative acknowledgment for efficiency  
**Scale/Scope**: Single state machine module (~1500-2000 LOC); integrates with existing 6-module project structure

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Functional Purity & Type Safety
- [x] All new code uses immutable data structures and ZIO effect types
  - State machine state stored in immutable case classes
  - All operations return ZIO effects, no direct side effects
- [x] No unsafe operations (casting, reflection) introduced
  - Using scodec for type-safe serialization
  - Pattern matching for type-safe command routing
- [x] Type safety preserved throughout implementation
  - Sealed traits for command types
  - Compile-time guarantees for state transitions

### II. Explicit Error Handling
- [x] All external interactions have explicit error handling
  - User state machines return results in response payload (no exceptions per FR-014)
  - Serialization errors explicitly handled via scodec Attempt types
- [x] Business logic errors use ZIO.fail or Either types, not exceptions
  - Errors modeled in response payload, cached for idempotency
  - State machine failures return UIO (cannot fail)
- [x] Timeout and resource failures properly modeled
  - Retry process handles timeouts via dirty read + command pattern
  - No direct timeouts in state machine logic (pure functions)

### III. Existing Code Preservation (NON-NEGOTIABLE)
- [x] Core interfaces (StateMachine, RPC, LogStore) not modified without architectural review
  - **EXTENDS existing zio.raft.StateMachine trait**, doesn't create new interface
  - SessionStateMachine implements StateMachine[SessionState, SessionCommand]
  - User state machines also implement StateMachine[UserState, UserCommand]
  - Integrates via existing RaftAction and ServerAction events
- [x] Backward compatibility maintained for public APIs
  - Purely additive: new module, no changes to existing modules
  - Uses ZIO Prelude State monad as per existing interface
  - Users wire state machine to existing events
- [x] No performance degradation without measurement and justification
  - O(1) idempotency checks via Map lookups
  - Dirty reads optimize retry process to avoid unnecessary Raft log entries

### IV. ZIO Ecosystem Consistency
- [x] ZIO primitives used for all concurrent operations
  - State machine is pure (no concurrency in core logic)
  - Users handle concurrency via existing ZIO Queue/Ref in integration layer
- [x] ZStream used for streaming, no external streaming libraries
  - No streaming in state machine itself (processes single commands)
  - Integration uses existing ZStream in RaftServer
- [x] Resource management follows ZIO Scope patterns
  - Stateless state machine (no resources to manage)
  - Snapshot dict lifecycle managed by user via existing Raft snapshot mechanism

### V. Test-Driven Maintenance
- [x] Bug fixes include reproducing test cases
  - Will create tests for each acceptance scenario before implementation
- [x] Performance changes include benchmark tests
  - Will benchmark idempotency check performance and snapshot size
- [x] Complex Raft scenarios have property-based tests
  - Property tests for: cumulative acks, idempotency invariants, snapshot restore

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
├── contracts/           # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

### Source Code (repository root)
```
# Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure]
```

**Structure Decision**: Option 1 (Single project) - This is a library module within the existing ZIO Raft project. Will create a new `state-machine` module following existing project structure.

## Phase 0: Outline & Research
1. **Extract unknowns from Technical Context** above:
   - For each NEEDS CLARIFICATION → research task
   - For each dependency → best practices task
   - For each integration → patterns task

2. **Generate and dispatch research agents**:
   ```
   For each unknown in Technical Context:
     Task: "Research {unknown} for {feature context}"
   For each technology choice:
     Task: "Find best practices for {tech} in {domain}"
   ```

3. **Consolidate findings** in `research.md` using format:
   - Decision: [what was chosen]
   - Rationale: [why chosen]
   - Alternatives considered: [what else evaluated]

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design & Contracts
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Generate API contracts** from functional requirements:
   - For each user action → endpoint
   - Use standard REST/GraphQL patterns
   - Output OpenAPI/GraphQL schema to `/contracts/`

3. **Generate contract tests** from contracts:
   - One test file per endpoint
   - Assert request/response schemas
   - Tests must fail (no implementation yet)

4. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

5. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh cursor`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, /contracts/*, failing tests, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
1. **Data Model Tasks** (from data-model.md):
   - Create core types (SessionState, ResponseCacheEntry, PendingServerRequest, CombinedState)
   - Create UserStateMachine trait (simplified - no snapshot methods)
   - Create SessionCommand ADT (extends Command)
   - Create codec infrastructure (SessionStateCodec, etc.)
   - [P] All type definitions can be done in parallel

2. **Contract Test Tasks** (from contracts/):
   - UserStateMachine interface contract tests
   - SessionStateMachine contract tests (composition + idempotency + cumulative ack)
   - Property-based tests for invariants (idempotency, cumulative ack, snapshot)
   - [P] Test files independent, can be written in parallel

3. **Implementation Tasks** (TDD - make tests pass):
   - Implement SessionStateMachine (takes UserStateMachine in constructor)
   - Implement idempotency checking logic
   - Implement cumulative acknowledgment logic
   - Implement snapshot serialization/deserialization (both session + user state)
   - Sequential (tests must exist first)

4. **Integration Tasks** (from quickstart.md):
   - Create CounterStateMachine example (implements UserStateMachine)
   - Instantiate SessionStateMachine with CounterStateMachine
   - Wire to RaftServer event stream
   - Integration tests with full flow
   - Sequential (depends on core implementation)

5. **Documentation Tasks**:
   - Update module README
   - Add scaladoc to public APIs
   - Final (after implementation complete)

**Ordering Strategy**:
- TDD order: Tests before implementation 
- Dependency order: Types → Codecs → SessionSM (with UserSM in constructor) → Integration
- Mark [P] for parallel execution (independent files)
- Estimated: 24-28 numbered tasks (simplified from 28-32 due to architecture simplification)

**Task Breakdown from Acceptance Scenarios**:
- Scenario 1-2: Idempotency checking (Tasks 10-12)
- Scenario 3: Integration wiring (Tasks 20-22)
- Scenario 4-5: Error handling and parallel requests (Tasks 13-14)
- Scenario 6-7: Server-initiated requests (Tasks 15-17)
- Scenario 8-9: Cumulative acknowledgment (Tasks 18-19)
- Scenario 10: Retry with dirty reads (Task 23)
- Scenario 11: Snapshot/restore (Tasks 24-25)

**IMPORTANT**: This phase is executed by the /tasks command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |


## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command) - research.md created
- [x] Phase 1: Design complete (/plan command) - data-model.md, contracts/, quickstart.md, agent file updated
- [x] Phase 2: Task planning complete (/plan command - describe approach only) - Strategy documented above
- [ ] Phase 3: Tasks generated (/tasks command) - NEXT STEP: Run /tasks
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS (all checkboxes marked)
- [x] Post-Design Constitution Check: PASS (design adheres to all constitutional principles)
- [x] All NEEDS CLARIFICATION resolved (none in Technical Context)
- [x] Complexity deviations documented (none - no violations)

**Artifacts Generated**:
- ✅ /specs/002-session-state-machine/plan.md (this file)
- ✅ /specs/002-session-state-machine/research.md (9 key decisions + module structure)
- ✅ /specs/002-session-state-machine/data-model.md (Map[String, Any] based state system)
- ✅ /specs/002-session-state-machine/contracts/state-machine-interface.md (UserStateMachine trait - 3 methods)
- ✅ /specs/002-session-state-machine/contracts/session-state-machine.md (SessionStateMachine + idempotency)
- ✅ /specs/002-session-state-machine/quickstart.md (complete counter example)
- ✅ /specs/002-session-state-machine/ARCHITECTURE_FINAL.md (ultra-simplified architecture)
- ✅ /.cursor/rules/specify-rules.mdc (updated with new tech stack info)

**Contract Test Count**: 11 postconditions + 6 invariants = 17 test specifications ready for implementation

**Ultra-Simplification**: 
- UserStateMachine = just 3 methods, no state type param, no codecs
- State always Map[String, Any]
- SessionStateMachine takes userSM + ONE codec
- No CombinedState, no separate classes

---

## Execution Summary

### ✅ Planning Complete

The `/plan` command has successfully completed Phases 0-2 (planning phases only). Here's what was accomplished:

**Phase 0 - Research** ✅
- 9 key architectural decisions documented
- **Critical: Use existing zio.raft.StateMachine trait** (Constitution III)
- Serialization strategy defined (scodec)
- Performance characteristics analyzed
- Integration patterns specified
- Module structure designed

**Phase 1 - Design & Contracts** ✅
- Complete data model with 10 core types
- 2 interface contracts (StateMachine, SessionStateMachine) with 11 postconditions
- 6 property-based invariants specified
- **Simplified architecture**: SessionStateMachine takes UserStateMachine in constructor (no separate ComposableStateMachine)
- Quickstart guide with working counter example
- Agent context file updated

**Phase 2 - Task Planning** ✅
- Task generation strategy documented
- 28-32 tasks estimated
- TDD ordering strategy defined
- Parallel execution opportunities identified ([P] markers)

### 📋 Design Highlights

**State Machine Architecture**:
- Pure functional design (no side effects)
- **SessionStateMachine** takes **UserStateMachine** in constructor (composition via dependency injection)
- SessionStateMachine extends existing `zio.raft.StateMachine` trait
- UserStateMachine = simplified trait (no snapshot methods - SessionSM handles snapshots)
- Shared dictionary snapshots with key prefixes ("session/", "user/")
- Cumulative acknowledgments for efficiency

**Key Optimizations**:
- O(1) idempotency checks via Map lookup
- Dirty reads to skip unnecessary Raft log entries
- Single command for retry (GetRequestsForRetry) atomically retrieves + updates

**Integration Model**:
- Library provides state machine logic only (pure functions)
- User responsible for wiring to client-server library
- Clear separation of concerns (state vs transport)

### 🚀 Next Steps

**Ready for `/tasks` command** to generate tasks.md with:
- Numbered, ordered implementation tasks
- TDD approach (tests first, then implementation)
- Parallel execution markers
- Success criteria for each task

**Implementation will create**:
- New `state-machine` module
- ~1200-1500 LOC (simplified architecture)
- 17 test specifications
- Integration examples

**Ultra-Simplified Architecture**:
```scala
// User implements UserStateMachine (just 3 methods, NO state type param!)
class CounterSM extends UserStateMachine[CounterCmd, CounterResp]:
  def apply(cmd: CounterCmd): State[Map[String, Any], (CounterResp, List[ServerReq])] = ???
  def onSessionCreated(sid, caps): State[Map[String, Any], List[ServerReq]] = State.succeed(Nil)
  def onSessionExpired(sid): State[Map[String, Any], List[ServerReq]] = State.succeed(Nil)

// User provides ONE codec for state serialization
val codec: Codec[(String, Any)] = ???

// Pass UserSM + codec to SessionStateMachine constructor
val sessionSM = new SessionStateMachine(counterSM, codec)

// SessionStateMachine extends zio.raft.StateMachine[Map[String, Any], SessionCommand[?, ?]]
// Handles: idempotency, caching, server requests, snapshots, session lifecycle
// State is Map[String, Any] - user picks keys, session uses "session/" prefix
// Works with decoded types - NO serialization in state machine!
```

**Architecture Diagram**:
```
┌───────────────────────────────────────────────────────────────────┐
│   SessionStateMachine[UserCmd, UserResp]                         │
│                                                                   │
│  extends zio.raft.StateMachine[Map[String, Any],                 │
│                                 SessionCommand[?, ?]]             │
├───────────────────────────────────────────────────────────────────┤
│  Constructor:                                                     │
│    userSM: UserStateMachine[UserCmd, UserResp]                   │
│    stateCodec: Codec[(String, Any)]    ← ONE codec for all!     │
│                                                                   │
│  State: Map[String, Any]                                         │
│    Keys with "session/" prefix - session management             │
│    Keys without prefix - user data                              │
│                                                                   │
│  Command Flow:                                                    │
│    ClientRequest(sid, rid, cmd) ──┐   cmd already decoded!      │
│                                    ├→ Check cache ──┐            │
│                                    │              Hit│Miss        │
│                                    │                 ↓            │
│                                    │         userSM.apply(cmd)   │
│                                    │                 ↓            │
│                                    └← Cache (resp, serverReqs)   │
│                                                                   │
│    SessionCreated → userSM.onSessionCreated() → serverReqs       │
│    SessionExpired → userSM.onSessionExpired() → serverReqs       │
│    ServerRequestAck → Remove ≤ N (cumulative)                    │
│                                                                   │
│  Snapshot:                                                        │
│    Uses stateCodec.encode((k, v)) for each Map entry            │
│    NO separation - user and session share same Map              │
└───────────────────────────────────────────────────────────────────┘
                              ▲
                              │ uses
                              │
┌───────────────────────────────────────────────────────────────────┐
│      UserStateMachine[Command, Response]  ← NO State param!     │
├───────────────────────────────────────────────────────────────────┤
│  def apply(cmd: C): State[Map[String, Any], (R, List[ServerReq])│
│  def onSessionCreated(...): State[Map[String, Any], List[...]]  │
│  def onSessionExpired(...): State[Map[String, Any], List[...]]  │
│                                                                   │
│  State always Map[String, Any] - user picks keys!               │
│  NO codecs, NO emptyState, NO snapshots!                        │
│  Returns server requests from all methods!                       │
└───────────────────────────────────────────────────────────────────┘
                              ▲
                              │ implements
                              │
                    ┌─────────────────────┐
                    │ CounterStateMachine │
                    │                     │
                    │ Command: CounterCmd │
                    │ Response: CounterRsp│
                    │                     │
                    │ Uses key: "counter" │
                    └─────────────────────┘
```

### 📚 Reference Documents

All planning documents are in `/specs/002-session-state-machine/`:
- `spec.md` - Feature specification (27 requirements)
- `plan.md` - This implementation plan
- `research.md` - Architectural decisions
- `data-model.md` - Type system and validation rules
- `contracts/` - Interface contracts and test specs
- `quickstart.md` - Developer tutorial
- `ARCHITECTURE_FINAL.md` - Final architecture summary

### 🎯 Success Criteria

Before marking implementation complete, verify:
- ✅ All 22 contract tests passing
- ✅ All 11 acceptance scenarios from spec verified
- ✅ Property-based tests for invariants passing
- ✅ Quickstart example working end-to-end
- ✅ Integration with existing RaftServer functional
- ✅ No constitutional violations
- ✅ Code coverage > 90% for state machine logic

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
