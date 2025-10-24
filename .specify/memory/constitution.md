<!--
Sync Impact Report:
Version: NEW → 1.0.0 (initial constitution)
Added Principles:
- I. Functional Purity & Type Safety
- II. Explicit Error Handling  
- III. Existing Code Preservation
- IV. ZIO Ecosystem Consistency
- V. Test-Driven Maintenance
Added Sections:
- Scala & ZIO Standards
- Code Review Process
Templates Updated:
✅ plan-template.md - Updated Constitution Check section with specific principles
✅ tasks-template.md - Updated task descriptions for ZIO/Scala compliance
✅ agent-file-template.md - Added ZIO Raft specific technologies and guidelines
✅ spec-template.md - No changes needed (already technology-agnostic)
Follow-up TODOs: None
-->

# ZIO Raft Constitution

## Core Principles

### I. Functional Purity & Type Safety
All code MUST be functionally pure with explicit effect types; Immutable data structures required except for controlled concurrency primitives (Ref, Queue); Type safety MUST be preserved - no casting, reflection, or unsafe operations; Use ZIO effect types (ZIO, UIO, URIO) to encode side effects in the type system.

**Rationale**: Functional purity ensures predictable, testable, and composable code. The Raft algorithm's correctness depends on deterministic state transitions, making functional programming essential for reliability.

### II. Explicit Error Handling
All errors MUST be represented in types using ZIO error channel or sealed trait hierarchies; No exceptions for business logic - use ZIO.fail or Either types; Every external interaction (RPC, storage) MUST have explicit error handling; Timeouts and resource failures MUST be modeled as first-class errors.

**Rationale**: Distributed systems like Raft face numerous failure modes. Explicit error types make failure handling visible, reviewable, and testable, preventing silent failures in critical consensus operations.

### III. Existing Code Preservation (NON-NEGOTIABLE) 
Existing abstractions MUST be preserved and extended, not replaced; Changes to core interfaces (StateMachine, RPC, LogStore) require architectural review; Backward compatibility MUST be maintained for public APIs; Performance characteristics of existing code MUST NOT degrade without explicit justification and measurement.

**Rationale**: The current codebase represents validated architectural decisions. Preserving proven abstractions reduces risk and maintains system stability while allowing controlled evolution.

### IV. ZIO Ecosystem Consistency
All concurrent operations MUST use ZIO primitives (Fiber, Ref, Queue, Semaphore); Streaming MUST use ZStream, not external libraries; Resource management MUST use ZIO Scope and Resource patterns; Configuration and dependency injection MUST use ZLayer when applicable; Time operations MUST use ZIO Clock service, never `java.time.Instant.now()` or `System.currentTimeMillis()`; Random operations MUST use ZIO Random service, never `java.util.UUID.randomUUID()` or `scala.util.Random`; All side effects MUST be captured in ZIO effects for testability and composability.

**Rationale**: Consistent use of ZIO ecosystem provides composability, testability, and integration benefits. Mixed paradigms create maintenance burden and reduce the benefits of ZIO's comprehensive effect system. Java time/random operations break functional purity and cannot be mocked for testing.

### V. Test-Driven Maintenance
All bug fixes MUST include failing test cases that reproduce the issue; Performance changes MUST include benchmark tests; Public API changes MUST include integration tests; Complex Raft scenarios (leader election, log replication) MUST have dedicated property-based tests; All tests MUST use ZIO Test's `suiteAll` macro and `assertTrue` smart assertions for better readability and macro-based error reporting; Test suites MUST avoid comma separation between tests for cleaner formatting.

**Rationale**: Consensus algorithms are notoriously difficult to implement correctly. Comprehensive testing prevents regressions and builds confidence in the system's correctness under various failure scenarios. Modern ZIO Test features provide superior readability and debugging experience.

## Scala & ZIO Standards

**Language Requirements**: Scala 3.3+ with strict compilation flags (-Wunused:imports); ZIO 2.1+ for effect management; All dependencies MUST be compatible with Scala 3 and ZIO 2; Type inference MUST be used appropriately - explicit types for public APIs, inference for local variables.

**Performance Standards**: Memory allocation MUST be minimized in hot paths (message processing, log operations); Network operations MUST use streaming APIs for large data; Blocking operations MUST be shifted to appropriate thread pools using ZIO.blocking; Performance-critical paths MUST be measured and documented.

## Code Review Process

**Review Requirements**: All changes MUST be reviewed by at least one team member familiar with distributed systems; Changes to consensus logic MUST be reviewed by someone familiar with the Raft algorithm; Performance-sensitive changes MUST include benchmark results; Breaking changes MUST include migration documentation.

**Quality Gates**: All tests MUST pass including property-based tests; Code coverage MUST NOT decrease; Linting and formatting MUST be clean; Documentation MUST be updated for API changes; Commit messages MUST reference related issues or design decisions.

## Governance

This constitution supersedes all other development practices and coding standards. All code reviews MUST verify compliance with these principles. Any deviation from core principles MUST be explicitly justified with technical rationale and approved by the project maintainer.

Amendments to this constitution require:
1. Documentation of the proposed change and rationale
2. Review period of at least one week for feedback  
3. Approval by project maintainer
4. Migration plan for existing code if needed
5. Update of all dependent templates and documentation

**Version**: 1.0.0 | **Ratified**: 2025-09-24 | **Last Amended**: 2025-09-24