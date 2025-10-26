
# Implementation Plan: Lowest Sequence Number Protocol for Client-Server

**Branch**: `003-lowest-sequence-number` | **Date**: October 26, 2025 | **Spec**: `/specs/003-lowest-sequence-number/spec.md`
**Input**: Feature specification from `/specs/003-lowest-sequence-number/spec.md`

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
6. Execute Phase 1 → data-model.md, quickstart.md, agent-specific template file (e.g., `CLAUDE.md` for Claude Code, `.github/copilot-instructions.md` for GitHub Copilot, `GEMINI.md` for Gemini CLI, `QWEN.md` for Qwen Code or `AGENTS.md` for opencode).
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
Introduce a mandatory `lowestPendingRequestId` on both ClientRequest representations: (1) the protocol-level ClientRequest (client → server message) and (2) the server-internal `RaftAction.ClientRequest`. This enables deterministic server-side cache eviction for all cached responses with requestId < K within a session, preventing unbounded cache growth while preserving exactly-once semantics. The server-side behavior is implemented inside the `SessionStateMachine` (not the client-server server). The server must: (1) evict entries < K atomically with request processing, (2) return RequestError without re-execution for duplicates below K (via a new server action `ServerAction.SendRequestError`), and (3) on receiving a RequestError, fail the pending request and terminate the app if still pending; otherwise ignore. On the client, K is computed as the minimum requestId from the client's `pendingRequests` set for the session.

## Technical Context
**Language/Version**: Scala 3.3+  
**Primary Dependencies**: ZIO 2.1+, existing client-server-protocol, session-state-machine  
**Storage**: Raft log + snapshots (unchanged)  
**Testing**: ZIO Test  
**Target Platform**: JVM  
**Project Type**: Single backend library (protocol + server)  
**Performance Goals**: No additional round-trips; O(1) eviction decision per request  
**Constraints**: Field is mandatory; no legacy client support  
**Scale/Scope**: Applies to all sessions and requests; no cache size limits needed when protocol enforced

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
- [x] Backward compatibility maintained for public APIs (protocol add is additive but mandatory moving forward)
- [x] No performance degradation without measurement and justification

### IV. ZIO Ecosystem Consistency
- [x] ZIO primitives used for all concurrent operations
- [x] ZStream used for streaming, no external streaming libraries
- [x] Resource management follows ZIO Scope patterns
- [x] `suiteAll` is used instead of `suite`

### V. Test-Driven Maintenance
- [x] Bug fixes include reproducing test cases
- [x] Performance changes include benchmark tests (eviction counters)
- [x] Complex Raft scenarios have property-based tests (monotonic K, duplicate below K → RequestError)

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── quickstart.md        # Phase 1 output (/plan command)
└── tasks.md             # Phase 2 output (/tasks command - NOT created by /plan)
```

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

**Output**: research.md with all NEEDS CLARIFICATION resolved (none remaining; proceed-without-clarifications approved)

## Phase 1: Design
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Extract test scenarios** from user stories:
   - Each story → integration test scenario
   - Quickstart test = story validation steps

3. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh cursor`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, quickstart.md, contracts/

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (data model, quickstart)
- Each entity → model creation task [P] 
- Each user story → integration test task
- Implementation tasks to make tests pass

**Ordering Strategy**:
- TDD order: Tests before implementation 
- Dependency order: Models before services before UI
- Mark [P] for parallel execution (independent files)

**Estimated Output**: 12-18 numbered, ordered tasks in tasks.md (protocol evolution scope)

## Scope Notes
- Server logic for eviction and duplicate handling is implemented in `session-state-machine` (inside `SessionStateMachine`), not in the `client-server-server` transport.
- Protocol changes occur in `client-server-protocol`: add `lowestPendingRequestId` to protocol ClientRequest.
- Server internal action changes: add `lowestPendingRequestId` to `RaftAction.ClientRequest`; introduce new server action `ServerAction.SendRequestError`; ensure mapping protocol → action preserves the field.
- Client logic: compute `lowestPendingRequestId` as `min(pendingRequests)` per session.

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
- [x] Phase 0: Research complete (/plan command)
- [x] Phase 1: Design complete (/plan command)
- [ ] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS
- [x] Post-Design Constitution Check: PASS
- [x] All NEEDS CLARIFICATION resolved (override approved; FR-007 specified: ClientRequest)
- [ ] Complexity deviations documented

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
