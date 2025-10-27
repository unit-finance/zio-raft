# Implementation Plan: Add client-server to kvstore, CLI, and watch command

**Branch**: `004-add-client-server` | **Date**: 2025-10-26 | **Spec**: /Users/somdoron/git/zio-raft/specs/004-add-client-server/spec.md
**Input**: Feature specification from `/specs/004-add-client-server/spec.md`

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
6. Execute Phase 1 → data-model.md, quickstart.md, agent-specific template file (if applicable)
7. Re-evaluate Constitution Check section
   → If new violations: Refactor design, return to Phase 1
   → Update Progress Tracking: Post-Design Constitution Check
8. Plan Phase 2 → Describe task generation approach (DO NOT create tasks.md)
9. STOP - Ready for /tasks command
```

## Summary
Use existing client-server libraries (Feature 001) and the session state machine (Feature 002) to replace the HTTP example in `kvstore`, add a `kvstore-cli` for demonstration, implement a watch command that returns the current value immediately and streams subsequent updates, manage per-session subscriptions, and introduce a stream-based `Node` that binds the Raft server, Raft core, and state machine: it consumes `RaftServer.raftActions`, maps them to `SessionCommand` and maps responses back to `ServerAction`; it also merges Raft core `stateNotifications` mapped to `RaftServer.stepUp`/`stepDown`; and it runs a 10s periodic retry stream that checks `hasPendingRequests` (dirty read) before initiating `SessionCommand.GetRequestsForRetry`.

## Technical Context
**Language/Version**: Scala 3.3+, ZIO 2.1+  
**Primary Dependencies**: ZIO, zio-raft client-server libraries (Feature 001), session-state-machine (Feature 002)  
**Storage**: In-memory example (no deletes)  
**Testing**: ZIO Test (suiteAll, assertTrue) mandated by Constitution  
**Target Platform**: JVM (Linux/macOS)  
**Project Type**: single (library + examples)  
**Performance Goals**: Reasonable dev-demo responsiveness; all updates delivered to watchers  
**Constraints**: 
- No HTTP server in example
- Unlimited concurrent watches
- No cross-session ordering guarantees; deliver all updates, no coalescing
- Watch returns current value immediately
- Duplicate watches idempotent
- Retry logic runs inside `Node`: must check `hasPendingRequests` before `GetRequestsForRetry`
- Reuse existing libraries, no duplicate mechanisms

### CLI Configuration
- Endpoints provided via `--endpoints` flag or `KVSTORE_ENDPOINTS` env var; defaults to localhost dev endpoints if unspecified.

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. Functional Purity & Type Safety
- [ ] All new code uses immutable data structures and ZIO effect types
- [ ] No unsafe operations (casting, reflection) introduced
- [ ] Type safety preserved throughout implementation

### II. Explicit Error Handling
- [ ] All external interactions have explicit error handling
- [ ] Business logic errors use ZIO.fail or Either types, not exceptions
- [ ] Timeout and resource failures properly modeled

### III. Existing Code Preservation (NON-NEGOTIABLE)
- [ ] Core interfaces (StateMachine, RPC, LogStore) not modified without architectural review
- [ ] Backward compatibility maintained for public APIs
- [ ] No performance degradation without measurement and justification

### IV. ZIO Ecosystem Consistency
- [ ] ZIO primitives used for all concurrent operations
- [ ] ZStream used for streaming, no external streaming libraries
- [ ] Resource management follows ZIO Scope patterns
- [ ] `suiteAll` is used instead of `suite`

### V. Test-Driven Maintenance
- [ ] Bug fixes include reproducing test cases
- [ ] Performance changes include benchmark tests
- [ ] Complex Raft scenarios have property-based tests

## Project Structure

### Documentation (this feature)
```
specs/004-add-client-server/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

## Phase 0: Outline & Research
1. Extract unknowns from Technical Context above:
   - Event payload fields → resolved: key, value
   - Delivery semantics → resolved: all updates, no coalescing, unordered across sessions
   - Reconnection/expiry → resolved: handled by client-server; expire-session means terminal
   - Limits → resolved: unlimited concurrent watches
2. Consolidate findings in `research.md` using Decision/Rationale/Alternatives

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design
*Prerequisites: research.md complete*

1. Extract entities from feature spec → `data-model.md`:
   - Session, Subscription, KeyValue, Notification/Event
2. Extract test scenarios from user stories → quickstart.md walkthrough
3. Define `Node` design: binds server, Raft core, and state machine; stream-consumes `raftActions` (→ `SessionCommand` → `ServerAction`), merges Raft `stateNotifications` (→ `RaftServer.stepUp`/`stepDown`), and runs 10s periodic retry stream using `hasPendingRequests` → `GetRequestsForRetry`. Publishing back to the server uses `RaftServer` methods: `sendClientResponse`, `sendServerRequest`, `sendRequestError`, and `confirmSessionCreation`.

**Output**: data-model.md, quickstart.md

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Generate tasks from Phase 1 design docs (data model, quickstart)
- Each entity → model update task [P]
- Each user story → integration test task
- Implementation tasks to make tests pass

**Ordering Strategy**:
- TDD order: Tests before implementation 
- Dependency order: State machine integration before CLI
- Mark [P] for parallel execution (independent files)

**Estimated Output**: 25-30 numbered, ordered tasks in tasks.md

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following constitutional principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [ ] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved
- [ ] Complexity deviations documented

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
