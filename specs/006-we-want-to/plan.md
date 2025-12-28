# Implementation Plan: TypeScript Client Library

**Branch**: `006-we-want-to` | **Date**: 2025-12-28 (Updated) | **Spec**: [spec.md](./spec.md)
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

**IMPORTANT**: The /plan command STOPS at step 8. Phases 2-4 are executed by other commands:
- Phase 2: /tasks command creates tasks.md
- Phase 3-4: Implementation execution (manual or via tools)

**UPDATE NOTE (2025-12-28)**: Plan regenerated after `/clarify` added CRITICAL architectural constraint requiring idiomatic TypeScript patterns everywhere (not just public API). See research.md Section 13 for detailed guidance.

## Summary
Create a TypeScript client library for Node.js that implements the ZIO Raft client-server protocol, matching the functionality of the existing Scala client. The library will use ZeroMQ for transport, support high-throughput request processing (1K-10K+ req/sec), and provide an event-based architecture for state observation without built-in logging. **CRITICAL**: While wire protocol must match Scala byte-for-byte, ALL implementation patterns must be idiomatic TypeScript/Node.js (see Constitution Check below).

## Technical Context
**Language/Version**: TypeScript 5.0+, Node.js 18+ LTS  
**Primary Dependencies**: zeromq (Node.js ZMQ bindings), Buffer API for binary framing  
**Storage**: N/A (stateless client library)  
**Testing**: Jest or Vitest for unit tests, integration tests against mock/real Raft cluster  
**Target Platform**: Node.js 18+ (server-side runtime)
**Project Type**: Single TypeScript library package  
**Performance Goals**: 1,000-10,000+ requests per second throughput with batching optimization  
**Constraints**: No built-in logging, event-loop based architecture, single session per client instance, **IDIOMATIC TYPESCRIPT EVERYWHERE**  
**Scale/Scope**: Client library with ~10-15 core modules (transport, protocol, state machine, pending requests/queries, framing)

## Constitution Check
*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**NOTE**: This feature implements a TypeScript client library for Node.js, not Scala/ZIO code. The ZIO Raft Constitution principles apply to the **server-side Scala implementation** that this client will communicate with, but NOT to this TypeScript client library itself.

### TypeScript-Specific Quality Standards (Adapted from Constitution)
Since the constitution is Scala/ZIO-specific, we adapt the spirit of its principles to TypeScript:

**Type Safety & Code Quality** (adapted from Constitution I):
- [x] Use TypeScript strict mode for maximum type safety
- [x] Prefer immutable data patterns (readonly, const) for message types and public interfaces
- [x] Use mutable private state where appropriate (not forced immutability everywhere)
- [x] Discriminated unions for type-safe message handling

**Explicit Error Handling** (adapted from Constitution II):
- [x] Synchronous exceptions for validation errors (per clarifications)
- [x] Promise rejection for async operation errors
- [x] Document all error conditions in type signatures and JSDoc

**Protocol Compatibility** (adapted from Constitution III):
- [x] MUST maintain wire protocol compatibility with Scala client-server implementation
- [x] No breaking changes to protocol message formats
- [x] Follow same session lifecycle and reconnection semantics as Scala client

**Ecosystem Consistency** (adapted from Constitution IV):
- [x] Use Node.js native patterns (EventEmitter for observability, not ZStream)
- [x] Use Buffer API for binary data handling
- [x] Use zeromq library for ZMQ transport
- [x] Use Promises/async-await (not ZIO effects)
- [x] Use classes with private state (not Ref objects)
- [x] Library should feel like ioredis, pg, or mongodb

**Test Coverage** (adapted from Constitution V):
- [x] Unit tests for all protocol message encoding/decoding
- [x] Integration tests for session lifecycle and reconnection scenarios
- [x] Performance tests to validate 1K-10K+ req/sec throughput target

### CRITICAL: Idiomatic TypeScript Requirement (Session 2025-12-28)
- [x] **ALL implementation patterns must be idiomatic TypeScript/Node.js**
- [x] Wire protocol layer: Match Scala byte-for-byte (codecs)
- [x] Everything else: Standard TypeScript patterns (see research.md Section 13)
- [x] No Scala pattern ports (ZRef → private fields, ZStream → EventEmitter, ZIO → Promise)
- [x] Classes with encapsulated mutable state where appropriate
- [x] Simple, clear APIs that TypeScript developers expect

## Project Structure

### Documentation (this feature)
```
specs/006-we-want-to/
├── spec.md             # Feature specification (complete)
├── plan.md             # This file (/plan command output - UPDATED 2025-12-28)
├── research.md         # Phase 0 output (/plan command - UPDATED with Section 13)
├── data-model.md       # Phase 1 output (/plan command)
├── tests.md            # Phase 1 output (/plan command)
├── design.md           # Phase 1 output (/plan command)
├── quickstart.md       # Phase 1 output (/plan command)
└── tasks.md            # Phase 2 output (/tasks command - WILL BE REGENERATED)
```

### TypeScript Library Structure (to be created/updated)
```
typescript-client/
├── package.json
├── tsconfig.json
├── src/
│   ├── index.ts                    # Public API exports
│   ├── client.ts                   # Main RaftClient class (EventEmitter-based)
│   ├── transport.ts                # ZMQ transport layer
│   ├── protocol/
│   │   ├── messages.ts             # Protocol message types
│   │   ├── codecs.ts               # Binary encoding/decoding (MUST match Scala)
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
**Status**: ✅ COMPLETE (research.md exists)
- All technical decisions documented in research.md
- **UPDATED 2025-12-28**: Added Section 13 with idiomatic TypeScript guidelines
- Wire protocol compatibility strategy defined
- TypeScript idiom patterns documented (DO/DON'T examples)
- Implementation review checklist provided

**Output**: research.md with all technical decisions and TypeScript idiom guidance

## Phase 1: Design
**Status**: ✅ COMPLETE (data-model.md, quickstart.md, design.md exist)
- Data model defined with branded types and protocol messages
- Quickstart guide shows idiomatic TypeScript usage
- Design documents outline architecture

**Note**: Design documents were created before the idiomatic TypeScript clarification. Implementation should follow research.md Section 13 guidelines, which supersede earlier Scala-influenced patterns.

**Output**: data-model.md, tests.md, design.md, quickstart.md, agent-specific file

## Phase 2: Task Planning Approach
**Status**: ⏳ NEEDS REGENERATION via `/tasks` command

The `/tasks` command will regenerate tasks.md incorporating the idiomatic TypeScript constraint:

**Task Generation Strategy** (Updated for Idiomatic TypeScript):

### Core Principles for Task Generation:
1. **Wire Protocol Tasks** (T009-T013): Keep as-is - must match Scala exactly
2. **State Management Tasks** (T017-T020): Update to emphasize idiomatic patterns:
   - RequestIdRef should be integrated into RaftClient (not standalone utility)
   - ServerRequestTracker should use mutable private state (not immutable updates)
3. **State Machine Tasks** (T021-T025): Implement with classes and private state
4. **Event System Tasks** (T026-T027): Use EventEmitter pattern
5. **Client API Tasks** (T028-T033): Focus on idiomatic Promise-based API

### Refactoring Tasks to Add:
- **T061**: Refactor RequestIdRef into RaftClient private field
- **T062**: Refactor ServerRequestTracker to use mutable private state
- **T063**: Review and simplify branded type helper objects in types.ts

### Module Creation Tasks (Already Complete, May Need Refactoring):
1. **Project Setup** (T001-T005): ✅ Complete - No changes needed
2. **Type Definitions** (T006-T008): ✅ Complete - Branded types are idiomatic, helpers OK
3. **Protocol Codecs** (T009-T013): ✅ Complete - Wire protocol layer is fine
4. **Transport Layer** (T014-T016): ✅ Complete - Already idiomatic (classes, async/await)
5. **State Management** (T017-T020): ⚠️ Needs refactoring (RequestIdRef, ServerRequestTracker)

### Remaining Tasks (Apply Idiomatic Patterns):
6. **State Machine** (T021-T025): Use classes with private state, not functional state updates
7. **Event System** (T026-T027): Use EventEmitter, not custom observables
8. **Client API** (T028-T033): Promise-based, simple class interface
9-13. **Testing, Optimization, Documentation** (T034-T060): Continue as planned

**Ordering Strategy**:
- Implementation-first approach (not TDD for this TypeScript library)
- Refactoring tasks after Client API implementation
- Mark [P] for parallel execution (independent files)

**Estimated Output**: 60-65 tasks including refactoring tasks

**IMPORTANT**: This phase is executed by the `/tasks` command, NOT by /plan

## Phase 3+: Future Implementation
*These phases are beyond the scope of the /plan command*

**Phase 3**: Task execution (/tasks command creates tasks.md)  
**Phase 4**: Implementation (execute tasks.md following TypeScript idiomatic principles)  
**Phase 5**: Validation (run tests, execute quickstart.md, performance validation)

## Complexity Tracking
*Fill ONLY if Constitution Check has violations that must be justified*

**No violations**: The TypeScript client follows adapted constitutional principles appropriate for TypeScript/Node.js development. The wire protocol compatibility requirement is satisfied without violating idiomatic TypeScript patterns.

## Progress Tracking
*This checklist is updated during execution flow*

**Phase Status**:
- [x] Phase 0: Research complete (/plan command) - **UPDATED 2025-12-28 with Section 13**
- [x] Phase 1: Design complete (/plan command)
- [x] Phase 2: Task planning complete (/plan command - describe approach only)
- [ ] Phase 3: Tasks generated (/tasks command) - **NEEDS REGENERATION**
- [ ] Phase 4: Implementation complete - **IN PROGRESS (5/13 phases done)**
- [ ] Phase 5: Validation passed

**Gate Status**:
- [x] Initial Constitution Check: PASS (adapted for TypeScript context)
- [x] Post-Design Constitution Check: PASS (design follows adapted principles)  
- [x] All NEEDS CLARIFICATION resolved (via /clarify command, Sessions 2025-12-24 and 2025-12-28)
- [x] Complexity deviations documented (N/A - straightforward client library)
- [x] **Idiomatic TypeScript constraint added** (2025-12-28)

**Implementation Status** (as of 2025-12-28):
- ✅ Phase 3.1: Project Setup (5 tasks)
- ✅ Phase 3.2: Core Type Definitions (3 tasks)
- ✅ Phase 3.3: Binary Protocol Codecs (5 tasks)
- ✅ Phase 3.4: Transport Layer (3 tasks)
- ✅ Phase 3.5: State Management Structures (4 tasks) - ⚠️ Needs refactoring for idioms
- ⏳ Phase 3.6-3.13: Remaining phases (35 tasks) - Will apply idiomatic patterns

**Next Action**: Run `/tasks` to regenerate tasks.md with refactoring tasks and idiomatic pattern guidance

---
*Based on Constitution v1.0.0 (adapted for TypeScript) - See `.specify/memory/constitution.md` and research.md Section 13*
