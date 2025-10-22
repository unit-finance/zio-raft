
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

This feature implements a session state machine framework for ZIO Raft that provides linearizable semantics and exactly-once command execution. Based on Chapter 6.3 of the Raft dissertation, the SessionStateMachine is an abstract base class that users extend to add session management to their business logic. The framework uses the template pattern - the base class defines the session management skeleton, users implement 3 abstract methods for their specific logic.

**Core Capability**: Developers extend SessionStateMachine and implement 3 protected methods (`applyCommand`, `handleSessionCreated`, `handleSessionExpired`) while the base class automatically handles:
- Idempotency checking via (sessionId, requestId) pairs
- Response caching for duplicate detection
- Server-initiated request tracking with cumulative acknowledgments
- State type safety via HMap with compile-time schema validation

**Architectural Approach (Template Pattern)**: The library provides an abstract base class with session management logic. Users extend this class and:
1. Define their schema (prefixes and types)
2. Implement 3 abstract methods for business logic
3. Implement serialization methods (choose their own library)
The base class provides the session management skeleton, users fill in specifics. No serialization dependencies in the library.

## Technical Context
**Language/Version**: Scala 3.3+  
**Primary Dependencies**: ZIO 2.1+ (no scodec - serialization is user's responsibility)  
**Storage**: State maintained in Raft log + snapshots; uses HMap with type-safe prefixes  
**Testing**: ZIO Test with `suiteAll` macro, property-based tests for state machine transitions  
**Target Platform**: JVM (cross-platform via Scala)  
**Project Type**: Single library module (`state-machine` or similar) integrated into existing ZIO Raft project  
**Performance Goals**: Deterministic O(1) idempotency checks; no size limits on response cache (for now); dirty reads for retry optimization  
**Constraints**: Must not modify client-server library interfaces; user state machines MUST NOT throw exceptions (errors in response payload); cumulative acknowledgment for efficiency; library is serialization-agnostic (users provide their own)  
**Scale/Scope**: Single state machine module (~1200-1500 LOC); integrates with existing 6-module project structure

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Functional Purity & Type Safety
- [x] All new code uses immutable data structures and ZIO effect types
  - State stored in immutable HMap with type-safe schema
  - All operations return ZIO effects, no direct side effects
- [x] No unsafe operations (casting, reflection) introduced
  - HMap provides compile-time type safety for state access
  - Pattern matching for type-safe command routing
  - Serialization delegated to user (library has no unsafe serialization code)
- [x] Type safety preserved throughout implementation
  - Sealed traits for command types
  - Compile-time schema validation via HMap
  - Compile-time guarantees for state transitions

### II. Explicit Error Handling
- [x] All external interactions have explicit error handling
  - User state machines return results in response payload (no exceptions per FR-014)
  - Serialization errors handled by user (library has no serialization code)
- [x] Business logic errors use ZIO.fail or Either types, not exceptions
  - Errors modeled in response payload, cached for idempotency
  - State machine failures return UIO (cannot fail)
- [x] Timeout and resource failures properly modeled
  - Retry process handles timeouts via dirty read + command pattern
  - No direct timeouts in state machine logic (pure functions)

### III. Existing Code Preservation (NON-NEGOTIABLE)
- [x] Core interfaces (StateMachine, RPC, LogStore) not modified without architectural review
  - **SessionStateMachine extends existing zio.raft.StateMachine trait**, doesn't create new interface
  - SessionStateMachine is abstract base class extending StateMachine[HMap[CombinedSchema], SessionCommand]
  - Users extend SessionStateMachine and implement 3 abstract methods
  - Integrates via existing RaftAction and ServerAction events
- [x] Backward compatibility maintained for public APIs
  - Purely additive: new module, no changes to existing modules
  - Uses ZIO Prelude State monad as per existing interface
  - Users extend base class and wire to existing events
- [x] No performance degradation without measurement and justification
  - O(1) idempotency checks via HMap/Map lookups
  - HMap has zero runtime overhead vs Map[String, Any]
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
   - Create core types (SessionMetadata, PendingServerRequest[SR])
   - Define SessionSchema type alias (4 prefixes with their value types)
   - Define CombinedSchema[UserSchema] type alias (concatenates SessionSchema and UserSchema)
   - Create SessionCommand[UC <: Command] ADT (extends Command with dependent type Response)
   - Create abstract SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple] class with:
     * 3 protected abstract methods (applyCommand, handleSessionCreated, handleSessionExpired)
     * Final template method (apply) that calls abstract methods
     * Abstract snapshot methods (takeSnapshot, restoreFromSnapshot)
   - [P] All type definitions can be done in parallel

2. **Contract Test Tasks** (from contracts/):
   - SessionStateMachine abstract method contracts (applyCommand, handleSessionCreated, handleSessionExpired)
   - SessionStateMachine template method tests (idempotency + cumulative ack + caching)
   - Property-based tests for invariants (idempotency, cumulative ack, snapshot)
   - HMap schema type safety tests
   - [P] Test files independent, can be written in parallel

3. **Implementation Tasks** (TDD - make tests pass):
   - Implement SessionStateMachine abstract base class with template method
   - Implement idempotency checking logic in template method
   - Implement cumulative acknowledgment logic
   - Implement state narrowing and merging helpers
   - Sequential (tests must exist first)

4. **Integration Tasks** (from quickstart.md):
   - Create CounterStateMachine example (extends SessionStateMachine)
   - Implement CounterSchema and 3 abstract methods
   - Implement serialization (takeSnapshot/restoreFromSnapshot) using scodec
   - Wire to RaftServer event stream
   - Integration tests with full flow
   - Sequential (depends on core implementation)

5. **Documentation Tasks**:
   - Update module README
   - Add scaladoc to public APIs
   - Final (after implementation complete)

**Ordering Strategy**:
- TDD order: Tests before implementation 
- Dependency order: Types → SessionSM (abstract base class) → Integration (user extends base)
- Mark [P] for parallel execution (independent files)
- Estimated: 22-26 numbered tasks (simplified due to template pattern + no library serialization)

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
- ✅ /specs/002-session-state-machine/plan.md (this file - updated with HMap + template pattern)
- ✅ /specs/002-session-state-machine/research.md (9 key decisions + module structure)
- ✅ /specs/002-session-state-machine/data-model.md (HMap-based type-safe template pattern)
- ✅ /specs/002-session-state-machine/contracts/state-machine-interface.md (abstract methods contract)
- ✅ /specs/002-session-state-machine/contracts/session-state-machine.md (SessionStateMachine + idempotency)
- ✅ /specs/002-session-state-machine/quickstart.md (complete counter example)
- ✅ /specs/002-session-state-machine/ARCHITECTURE_FINAL.md (template pattern architecture)
- ✅ /.cursor/rules/specify-rules.mdc (updated with new tech stack info)
- ✅ /raft/src/main/scala/zio/raft/HMap.scala (type-safe heterogeneous map - already implemented)

**Contract Test Count**: 11 postconditions + 6 invariants + 1 schema safety = 18 test specifications ready for implementation

**Type-Safe Template Pattern with HMap**: 
- SessionStateMachine[UC, SR, UserSchema] = abstract base class with 3 protected abstract methods
- Users extend SessionStateMachine and implement methods for their business logic
- SessionSchema = fixed 4-prefix schema for session management
- CombinedSchema[U] = type-level concatenation of SessionSchema and UserSchema
- Compile-time type checking for all state access
- Zero runtime overhead vs Map[String, Any]
- No serialization dependencies in library

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

**State Machine Architecture (Template Pattern)**:
- Pure functional design (no side effects)
- **Type-safe state with HMap** - compile-time checking for all state access
- **SessionStateMachine** is abstract base class that users extend (template pattern)
- SessionStateMachine extends existing `zio.raft.StateMachine` trait
- Users implement 3 protected abstract methods for their business logic
- Users implement serialization methods (library has no serialization dependencies)
- **Schema-driven design**: SessionSchema + UserSchema → CombinedSchema (type-level concatenation)
- State narrowing: pass HMap[UserSchema] to user methods, automatically merged back
- Template method `apply` is final - defines session management flow
- Cumulative acknowledgments for efficiency

**Key Optimizations**:
- O(1) idempotency checks via Map lookup
- Dirty reads to skip unnecessary Raft log entries
- Single command for retry (GetRequestsForRetry) atomically retrieves + updates

**Integration Model (Template Pattern)**:
- Library provides abstract base class with session management skeleton
- User extends base class and implements 3 abstract methods + serialization
- User responsible for wiring to client-server library
- Clear separation of concerns (session management vs business logic vs serialization)

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

**Type-Safe Template Pattern with HMap**:
```scala
// User defines their Command type (extends Command with dependent Response)
sealed trait CounterCmd extends Command
object CounterCmd:
  case class Increment(by: Int) extends CounterCmd:
    type Response = Int

// User defines their schema (prefixes and types)
type CounterSchema = 
  ("counter", Int) *:
  EmptyTuple

// User extends SessionStateMachine (template pattern!)
class CounterSM extends SessionStateMachine[CounterCmd, ServerReq, CounterSchema]:
  
  // Implement 3 abstract methods
  protected def applyCommand(cmd: CounterCmd): State[HMap[CounterSchema], (cmd.Response, List[ServerReq])] = 
    State.modify { state =>
      cmd match
        case Increment(by) =>
          val current = state.get["counter"]("value").getOrElse(0)  // Type: Option[Int]
          val newState = state.updated["counter"]("value", current + by)  // Type-checked!
          (newState, (current + by, Nil))
    }
  
  protected def handleSessionCreated(sid, caps): State[HMap[CounterSchema], List[ServerReq]] = 
    State.succeed(Nil)
  
  protected def handleSessionExpired(sid): State[HMap[CounterSchema], List[ServerReq]] = 
    State.succeed(Nil)
  
  // Implement serialization (user chooses library - example uses scodec)
  def takeSnapshot(state: HMap[CombinedSchema[CounterSchema]]): Stream[Nothing, Byte] = ???
  
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[CounterSchema]]] = ???

// Use the state machine
val counterSM = new CounterSM()

// counterSM extends zio.raft.StateMachine[HMap[CombinedSchema[CounterSchema]], SessionCommand[CounterCmd]]
// CombinedSchema = SessionSchema ++ CounterSchema (type-level concatenation)
// Base class handles: idempotency, caching, server requests, session lifecycle
// User implements: business logic + serialization
// Compile-time type safety for all state access!
```

**Architecture Diagram (Template Pattern)**:
```
┌────────────────────────────────────────────────────────────────────────┐
│   SessionStateMachine[UC <: Command, SR, UserSchema <: Tuple]         │
│   (ABSTRACT BASE CLASS)                                                │
│                                                                        │
│  extends zio.raft.StateMachine[HMap[CombinedSchema[UserSchema]],      │
│                                 SessionCommand[UC]]                    │
├────────────────────────────────────────────────────────────────────────┤
│  Schema (Type Level):                                                  │
│    SessionSchema = ("metadata", SessionMetadata) *:                   │
│                    ("cache", Any) *:                                  │
│                    ("serverRequests", PendingServerRequest[?]) *:     │
│                    ("lastServerRequestId", RequestId) *: EmptyTuple   │
│    CombinedSchema[U] = Tuple.Concat[SessionSchema, U]                │
│                                                                        │
│  State: HMap[CombinedSchema[UserSchema]]   ← Type-safe!               │
│    Access: state.get["metadata"](key) → Option[SessionMetadata]      │
│    Update: state.updated["cache"](key, resp) → HMap (type-checked!)  │
│                                                                        │
│  Template Method (FINAL):                                             │
│    final def apply(command: SessionCommand[UC]) =                     │
│      ClientRequest(sid, rid, cmd) ──┐   cmd already decoded!         │
│                                      ├→ Check cache ──┐               │
│                                      │              Hit│Miss           │
│                                      │                 ↓               │
│                                      │    state.narrowTo[UserSchema]  │
│                                      │    applyCommand(cmd) ← ABSTRACT│
│                                      │    mergeUserState(...)         │
│                                      │                 ↓               │
│                                      └← Cache (resp, serverReqs)      │
│                                                                        │
│      SessionCreated → narrow → handleSessionCreated() ← ABSTRACT     │
│      SessionExpired → narrow → handleSessionExpired() ← ABSTRACT     │
│      ServerRequestAck → Remove ≤ N (cumulative)                       │
│                                                                        │
│  Abstract Methods (USER IMPLEMENTS):                                   │
│    protected def applyCommand(cmd: UC): State[HMap[UserSchema], ...] │
│    protected def handleSessionCreated(...): State[HMap[UserSchema],...│
│    protected def handleSessionExpired(...): State[HMap[UserSchema],...│
│    def takeSnapshot(state): Stream[Nothing, Byte]  ← USER SERIALIZES │
│    def restoreFromSnapshot(stream): UIO[HMap[...]] ← USER DESERIALIZES│
└────────────────────────────────────────────────────────────────────────┘
                              ▲
                              │ extends (template pattern)
                              │
                    ┌──────────────────────────┐
                    │  CounterStateMachine     │
                    │  (USER'S CONCRETE CLASS) │
                    ├──────────────────────────┤
                    │  Command: CounterCmd     │
                    │  Response: Int           │
                    │  Schema: CounterSchema   │
                    │    = ("counter", Int) *: │
                    │      EmptyTuple          │
                    │                          │
                    │  Implements 3 methods:   │
                    │   - applyCommand         │
                    │   - handleSessionCreated │
                    │   - handleSessionExpired │
                    │   - takeSnapshot (scodec)│
                    │   - restoreFromSnapshot  │
                    └──────────────────────────┘
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
