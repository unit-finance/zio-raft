
# Implementation Plan: Client-server Query support (+ kvstore GET via Query)

**Branch**: `005-client-server-libraries` | **Date**: 2025-10-28 | **Spec**: specs/005-client-server-libraries/spec.md
**Input**: Feature specification from `/specs/005-client-server-libraries/spec.md`

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
Add Query support to client-server libraries and adopt it in the kvstore example by replacing the current GET command with a Query-based GET. Queries are read-only, leader-served for linearizable semantics, carry a client-generated correlationId (opaque string), have no auth, and use the same retry defaults/configuration as ClientRequest.

## Technical Context
**Language/Version**: Scala 3  
**Primary Dependencies**: ZIO 2, existing zio-raft client/server libraries  
**Storage**: N/A (read-only Query, no persistence change)  
**Testing**: ZIO Test  
**Target Platform**: JVM/Linux dev
**Project Type**: multi-module (treated as single repo feature)  
**Performance Goals**: SAME as ClientRequest read path  
**Constraints**: Linearizable reads; leader-served; retry and payload limits same as ClientRequest  
**Scale/Scope**: SAME as existing kvstore example

Note: Implementation detail mapping — server will produce an internal `ServerAction.Query` to represent handling of business-level Query and then emit a `QueryResponse` to the client. This clarifies terminology used in tasks.

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

**Output**: research.md with decisions on Query semantics, correlationId, retries, auth, and kvstore GET migration

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

**Output**: data-model.md, failing tests, quickstart.md (includes kvstore GET via Query), agent-specific file

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

**Estimated Output**: 25-30 numbered, ordered tasks in tasks.md

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
- [ ] Phase 0: Research complete (/plan command)
- [ ] Phase 1: Design complete (/plan command)
- [ ] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [ ] Initial Constitution Check: PASS
- [ ] Post-Design Constitution Check: PASS
- [ ] All NEEDS CLARIFICATION resolved
- [ ] Complexity deviations documented

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
