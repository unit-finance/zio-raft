# Implementation Plan: TypeScript Client Library

**Branch**: `006-we-want-to` | **Date**: 2025-12-24 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-we-want-to/spec.md`

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
Create a TypeScript client library for Node.js that implements the ZIO Raft client-server protocol, matching the functionality of the existing Scala client. The library will use ZeroMQ for transport, support high-throughput request processing (1K-10K+ req/sec), and provide an event-based architecture for state observation without built-in logging.

## Technical Context
**Language/Version**: TypeScript 5.0+, Node.js 18+ LTS  
**Primary Dependencies**: zeromq (Node.js ZMQ bindings), Buffer API for binary framing  
**Storage**: N/A (stateless client library)  
**Testing**: Jest or Vitest for unit tests, integration tests against mock/real Raft cluster  
**Target Platform**: Node.js 18+ (server-side runtime)
**Project Type**: Single TypeScript library package  
**Performance Goals**: 1,000-10,000+ requests per second throughput with batching optimization  
**Constraints**: No built-in logging, event-loop based architecture, single session per client instance  
**Scale/Scope**: Client library with ~10-15 core modules (transport, protocol, state machine, pending requests/queries, framing)

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**NOTE**: This feature implements a TypeScript client library for Node.js, not Scala/ZIO code. The ZIO Raft Constitution principles apply to the **server-side Scala implementation** that this client will communicate with, but NOT to this TypeScript client library itself.

### TypeScript-Specific Quality Standards
Since the constitution is Scala/ZIO-specific, we adapt the spirit of its principles to TypeScript:

**Type Safety & Purity** (adapted from Constitution I):
- [x] Use TypeScript strict mode for maximum type safety
- [x] Prefer immutable data patterns (readonly, const)
- [x] Minimize side effects, clearly separate pure functions from effectful operations

**Explicit Error Handling** (adapted from Constitution II):
- [x] Use Result/Either patterns or Promise rejection for error handling
- [x] Synchronous exceptions for validation errors (per clarifications)
- [x] Document all error conditions in type signatures

**Protocol Compatibility** (adapted from Constitution III):
- [x] Must maintain wire protocol compatibility with Scala client-server implementation
- [x] No breaking changes to protocol message formats
- [x] Follow same session lifecycle and reconnection semantics as Scala client

**Ecosystem Consistency** (adapted from Constitution IV):
- [x] Use Node.js native patterns (EventEmitter or similar for event loop)
- [x] Use Buffer API for binary data handling
- [x] Use zeromq library for ZMQ transport

**Test Coverage** (adapted from Constitution V):
- [x] Unit tests for all protocol message encoding/decoding
- [x] Integration tests for session lifecycle and reconnection scenarios
- [x] Performance tests to validate 1K-10K+ req/sec throughput target

## Project Structure

### Documentation (this feature)
```
specs/006-we-want-to/
├── spec.md             # Feature specification (complete)
├── plan.md             # This file (/plan command output)
├── research.md         # Phase 0 output (/plan command)
├── data-model.md       # Phase 1 output (/plan command)
├── tests.md            # Phase 1 output (/plan command)
├── design.md           # Phase 1 output (/plan command)
├── quickstart.md       # Phase 1 output (/plan command)
└── tasks.md            # Phase 2 output (/tasks command - NOT created by /plan)
```

### TypeScript Library Structure (to be created)
```
typescript-client/
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts                    # Public API exports
│   ├── client.ts                   # Main RaftClient class
│   ├── transport.ts                # ZMQ transport layer
│   ├── protocol/
│   │   ├── messages.ts             # Protocol message types
│   │   ├── codecs.ts               # Binary encoding/decoding
│   │   └── frame.ts                # Binary framing utilities
│   ├── state/
│   │   ├── clientState.ts          # State machine for client
│   │   ├── pendingRequests.ts     # Pending command tracking
│   │   └── pendingQueries.ts      # Pending query tracking
│   ├── config.ts                   # Client configuration
│   └── types.ts                    # Shared type definitions
└── tests/
    ├── unit/                       # Unit tests
    └── integration/                # Integration tests
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

The /tasks command will generate ordered implementation tasks from the design documents:

### Module Creation Tasks (Sequential Foundation)
1. **Project Setup** [P]: Initialize typescript-client directory, package.json, tsconfig.json, dependencies
2. **Type Definitions** [P]: Implement branded types (SessionId, RequestId, MemberId, Nonce, CorrelationId)
3. **Protocol Constants**: Define protocol signature, version, message type enums
4. **Message Types**: Define all ClientMessage and ServerMessage type definitions

### Binary Protocol Implementation (Sequential)
5. **Encoding Primitives**: Implement basic encoding functions (string, buffer, map, timestamp, enums)
6. **Decoding Primitives**: Implement basic decoding functions
7. **Client Message Codecs**: Implement encode for all ClientMessage types
8. **Server Message Codecs**: Implement decode for all ServerMessage types
9. **Protocol Header**: Implement header encode/decode with signature and version validation

### Transport Layer (Sequential)
10. **Transport Interface**: Define ClientTransport interface
11. **ZMQ Transport**: Implement ZmqTransport with socket configuration, connect/disconnect
12. **Mock Transport** [P]: Implement mock transport for testing

### State Management (Sequential)
13. **Request ID Counter**: Implement RequestIdRef with thread-safe increment
14. **Pending Requests Tracker**: Implement PendingRequests with add/complete/resend/timeout
15. **Pending Queries Tracker**: Implement PendingQueries (similar to requests but with CorrelationId)
16. **Server Request Tracker**: Implement ServerRequestTracker for deduplication

### State Machine Implementation (Sequential, Critical Path)
17. **State Types**: Define ClientState discriminated union types
18. **Disconnected State Handler**: Implement Disconnected state event handling
19. **ConnectingNewSession State Handler**: Implement connection flow for new sessions
20. **ConnectingExistingSession State Handler**: Implement reconnection flow for existing sessions
21. **Connected State Handler**: Implement connected state with all operation handlers

### Event System (Parallel with State Machine)
22. **Event Types** [P]: Define all ClientEvent types
23. **Event Emitter** [P]: Implement event emission utilities

### Client API (Sequential, Depends on State Machine)
24. **Client Configuration**: Implement ClientConfig with validation
25. **Action Queue**: Implement async action queue
26. **Unified Event Loop**: Implement event stream merging (actions, messages, timers)
27. **RaftClient Class**: Implement main RaftClient class with public API methods
28. **Client Lifecycle**: Implement connect/disconnect, run loop

### Testing (After Each Module)
29. **Codec Tests** [P]: Unit tests for all encoding/decoding (TC-CODEC-001 through TC-CODEC-304)
30. **State Machine Tests** [P]: Unit tests for state transitions (TC-STATE-001 through TC-STATE-401)
31. **Transport Tests** [P]: Unit tests for ZMQ transport (TC-TRANSPORT-001 through TC-TRANSPORT-006)
32. **Pending Tracker Tests** [P]: Unit tests for request/query tracking (TC-PENDING-001 through TC-PENDING-103)
33. **Integration Tests**: End-to-end tests against mock/real server (TC-INTEGRATION-001 through TC-INTEGRATION-302)
34. **Performance Tests**: Throughput and latency benchmarks (TC-PERF-001 through TC-PERF-102)
35. **Compatibility Tests**: Protocol compatibility with Scala server (TC-COMPAT-001 through TC-COMPAT-102)

### Documentation (Parallel with Implementation)
36. **API Documentation** [P]: Generate TypeDoc from code comments
37. **README** [P]: Write library README with installation and usage
38. **Examples** [P]: Create example applications (if scope expanded)

### Performance Optimization (After Core Complete)
39. **Request Batching** [P]: Implement batching for high throughput
40. **Buffer Pooling** [P]: Implement buffer reuse for encoding

**Ordering Strategy**:
- **Foundation First**: Types, constants, message definitions
- **Protocol Layer**: Encoding/decoding before transport
- **Transport Layer**: Socket operations before state machine
- **State Management**: Tracking structures before state machine
- **State Machine**: Core logic after all dependencies ready
- **Client API**: Public interface wraps state machine
- **Testing**: Unit tests after each module, integration tests at end
- **Optimization**: After core functionality validated

**Parallelization Opportunities** (marked with [P]):
- Type definitions and constants can be done concurrently
- Transport interface and mock can be developed in parallel with protocol codecs
- Event types can be defined in parallel with state machine
- Test files can be written in parallel once modules are implemented
- Documentation can be written alongside implementation

**Estimated Output**: 35-40 numbered, ordered tasks in tasks.md

**Dependencies**:
- Tasks 1-4: Project foundation (no dependencies)
- Tasks 5-9: Protocol layer (depends on 2-4)
- Tasks 10-12: Transport layer (depends on 3-4)
- Tasks 13-16: State tracking (depends on 2-3)
- Tasks 17-21: State machine (depends on 5-16)
- Tasks 22-23: Events (depends on 3-4)
- Tasks 24-28: Client API (depends on 17-23)
- Tasks 29-35: Testing (depends on corresponding implementation tasks)
- Tasks 36-38: Documentation (can run parallel with implementation)
- Tasks 39-40: Optimization (depends on 24-28, 33-35)

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
- [x] Initial Constitution Check: PASS (adapted for TypeScript context)
- [x] Post-Design Constitution Check: PASS (design follows adapted principles)
- [x] All NEEDS CLARIFICATION resolved (via /clarify command)
- [x] Complexity deviations documented (N/A - straightforward client library)

---
*Based on Constitution v1.0.0 - See `.specify/memory/constitution.md`*
