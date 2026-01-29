
# Implementation Plan: TypeScript Client Library

**Branch**: `006-we-want-to` | **Date**: 2025-12-29 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/Users/keisar/Desktop/Projects/Unit/zio-raft/specs/006-we-want-to/spec.md`

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
Build a professional, idiomatic TypeScript/Node.js client library that implements the ZIO Raft client-server wire protocol for JavaScript/TypeScript applications. The client will provide a clean, modern API following Node.js ecosystem patterns (EventEmitter, Promises, classes) while maintaining byte-for-byte wire protocol compatibility with the Scala client. Key features include lazy initialization with explicit connect(), automatic reconnection with session resumption, request queueing with configurable timeouts, minimal event-based observability, and high-throughput support (1K-10K+ req/sec). The library should feel natural to TypeScript developers, similar to popular database clients like ioredis, pg, or mongodb.

## Technical Context
**Language/Version**: TypeScript 5.x (ES2022 target), Node.js 18+ runtime  
**Primary Dependencies**: zeromq (Node.js ZMQ bindings), @msgpack/msgpack (binary framing/serialization)  
**Storage**: N/A (stateless client library)  
**Testing**: Vitest (unit/integration tests), TypeScript type checking  
**Target Platform**: Node.js 18+ on Linux/macOS/Windows  
**Project Type**: Library (single TypeScript package published to npm)  
**Performance Goals**: 1,000-10,000+ requests/second throughput, <10ms client-side latency overhead  
**Constraints**: Wire protocol byte-for-byte compatible with Scala client-server-protocol; idiomatic TypeScript/Node.js patterns required (no Scala ports); zero built-in logging (application-level observability only); single session per client instance  
**Scale/Scope**: ~15-20 TypeScript source files, comprehensive type definitions, protocol message encoding/decoding, connection state machine, request queue management, minimal event system

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**IMPORTANT**: This is a TypeScript/Node.js library, not Scala/ZIO code. Constitution principles are interpreted for TypeScript context.

### I. Functional Purity & Type Safety (TypeScript Interpretation)
- [x] All new code uses TypeScript with strict mode enabled (`strict: true`)
- [x] No `any` types used; proper type safety throughout with explicit types for public APIs
- [x] Type safety preserved throughout implementation with comprehensive type definitions
- **Note**: TypeScript does not enforce functional purity like ZIO; classes with private mutable state are acceptable and idiomatic

### II. Explicit Error Handling (TypeScript Interpretation)
- [x] All external interactions (ZMQ transport, network) have explicit error handling via Promise rejection
- [x] Custom error types defined for different failure modes (TimeoutError, ValidationError, ConnectionError, SessionExpiredError)
- [x] Timeout and resource failures properly modeled as rejected Promises with typed errors
- **Note**: TypeScript uses exceptions and Promise rejection patterns, not ZIO.fail or Either types

### III. Existing Code Preservation (NON-NEGOTIABLE)
- [x] Core server interfaces (StateMachine, RPC, LogStore) NOT modified - client is separate library
- [x] Wire protocol backward compatibility maintained - byte-for-byte compatible with Scala client
- [x] No server performance degradation - client is independent TypeScript package
- **Note**: This is a new client library; existing Scala client and server remain unchanged

### IV. ZIO Ecosystem Consistency (NOT APPLICABLE)
- [N/A] TypeScript client uses Node.js ecosystem patterns, not ZIO primitives
- [N/A] EventEmitter for events (not ZStream), Promises for async (not ZIO effects)
- [N/A] Standard Node.js resource patterns (cleanup in disconnect/destroy methods)
- **Note**: Wire protocol must match Scala byte-for-byte, but implementation is idiomatic TypeScript as per spec requirements

### V. Test-Driven Maintenance (TypeScript Interpretation)
- [x] All new features include Vitest unit/integration tests
- [x] Wire protocol compatibility verified with tests against Scala server
- [x] Performance characteristics validated with throughput tests
- [x] Edge cases from spec (reconnection, session expiry, queueing) covered by tests

## Project Structure

### Documentation (this feature)
```
specs/[###-feature]/
├── plan.md              # This file (/plan command output)
├── research.md          # Phase 0 output (/plan command)
├── data-model.md        # Phase 1 output (/plan command)
├── tests.md             # Phase 1 output (/plan command)
├── design.md            # Phase 1 output (/plan command)
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

**Output**: research.md with all NEEDS CLARIFICATION resolved

## Phase 1: Design
*Prerequisites: research.md complete*

1. **Extract entities from feature spec** → `data-model.md`:
   - Entity name, fields, relationships
   - Validation rules from requirements
   - State transitions if applicable

2. **Update plan with high-level design and architecture** → `design.md`
   - Design high-level solution, based on the `research.md`, `data-model.md`, `plan.md`, and `spec.md`. 
   - Read the relevant chapter from the Raft Paper, you can find it over in memory folder.
   - What new projects are you going to add?
   - What are the new dependencies?
   - What files are going to be changed?
   - What new entities or classes you need to add?
   - What areas are affected? Where do we need to add more tests to cover the new functionality?
   - What procotol changes are required, if any?
   - Do we need a new version of a codec for the protocol change?
   - Do we need a new protocol?
   - In general, give high-level overview of the solution in plain english and drawing as well.

3. **Extract test scenarios from user stories** → `tests.md`:
   - Each story → integration test scenario
   - For each functional requirement, evaluate if a test case is required → test case
   - Each new entity that requires codec → codec test case
   - For each edge case, prompt the user if a test is required → test case
   - Based on the `design.md`, what additional test cases we need to add?
   - Collect the different test cases in the `tests.md` in plain english
   - We are NOT doing Test Driven Development. Only collect the tests to the `tests.md` file.
   - Quickstart test = story validation steps

4. **Update agent file incrementally** (O(1) operation):
   - Run `.specify/scripts/bash/update-agent-context.sh cursor`
     **IMPORTANT**: Execute it exactly as specified above. Do not add or remove any arguments.
   - If exists: Add only NEW tech from current plan
   - Preserve manual additions between markers
   - Update recent changes (keep last 3)
   - Keep under 150 lines for token efficiency
   - Output to repository root

**Output**: data-model.md, tests.md, design.md, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
*This section describes what the /tasks command will do - DO NOT execute during /plan*

**Task Generation Strategy**:
- Load `.specify/templates/tasks-template.md` as base
- Generate tasks from Phase 1 design docs (data-model.md, design.md, tests.md, quickstart.md)
- Task categories:
  1. **Project Setup**: package.json, tsconfig.json, build configuration
  2. **Type Definitions**: Core types (SessionId, RequestId, etc.) in types.ts
  3. **Protocol Layer**: Codec implementation (message encoding/decoding)
  4. **Transport Layer**: ZMQ transport wrapper
  5. **State Machine**: All state implementations (Disconnected, ConnectingNew, ConnectingExisting, Connected)
  6. **Request Management**: PendingRequests, PendingQueries, ServerRequestTracker
  7. **Client API**: Main RaftClient class with public API
  8. **Event System**: TypedEventEmitter wrapper
  9. **Utilities**: AsyncQueue, RequestIdRef, stream merger
  10. **Error Types**: Custom error class hierarchy
  11. **Unit Tests**: Protocol, state machine, request management tests
  12. **Integration Tests**: Full lifecycle, reconnection, compatibility tests
  13. **Documentation**: README, API docs, examples

**Ordering Strategy**:
- **No TDD**: Implementation tasks before test tasks
- **Dependency order**: 
  - Types → Protocol → Transport → State Machine → Client API
  - Utilities built in parallel with main implementation [P]
  - Tests after corresponding implementation complete
- **Parallel execution** marked with [P] for independent files/modules

**Estimated Output**: 35-40 numbered, ordered tasks in tasks.md

**Key Task Breakdown**:
1. Project setup (package.json, tsconfig, build scripts) [P]
2. Core type definitions (types.ts, errors.ts) [P]
3. Protocol constants and message types [P]
4. Field-level codecs (encode/decode primitives)
5. Message-level codecs (ClientMessage, ServerMessage)
6. ZMQ transport implementation
7. Mock transport for testing [P]
8. State machine base types (ClientState discriminated union)
9. DisconnectedState handler
10. ConnectingNewSessionState handler
11. ConnectingExistingSessionState handler
12. ConnectedState handler
13. PendingRequests manager
14. PendingQueries manager
15. ServerRequestTracker
16. AsyncQueue utility [P]
17. RequestIdRef counter [P]
18. Stream merger utility [P]
19. TypedEventEmitter wrapper [P]
20. RaftClient main class (constructor, lifecycle methods)
21. RaftClient command/query submission methods
22. RaftClient event loop implementation
23-30. Unit tests (protocol codecs, state handlers, request management)
31-35. Integration tests (lifecycle, reconnection, compatibility)
36. README documentation
37. API documentation generation
38. Example applications
39-40. Performance benchmarks

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
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [x] Phase 3: Tasks generated (/tasks command)
- [ ] Phase 4: Implementation complete
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS (TypeScript interpretation applied)
- [x] Post-Design Constitution Check: PASS (no new violations)
- [x] All NEEDS CLARIFICATION resolved (via /clarify command sessions)
- [x] Complexity deviations documented (none - straightforward TypeScript library)

**Artifacts Generated**:
- [x] research.md - All technical decisions documented
- [x] data-model.md - Complete entity and type definitions
- [x] design.md - High-level architecture with diagrams
- [x] tests.md - 95 test scenarios cataloged
- [x] quickstart.md - End-to-end validation guide
- [x] Agent context updated - TypeScript client added to Cursor rules
- [x] tasks.md - 50 ordered implementation tasks generated

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
