# ZIO Raft Development Guidelines

Auto-generated from all feature plans. Last updated: [DATE]

## Active Technologies
- **Language**: Scala 3.3+ with ZIO 2.1+
- **Effect System**: ZIO (UIO, URIO, ZIO types required)
- **Testing**: ZIO Test with property-based testing
- **Build**: SBT with strict compilation flags
- **Code Quality**: scalafmt, scalafix, unused import warnings

## Project Structure
```
raft/src/main/scala/zio/raft/
├── Raft.scala              # Core consensus algorithm
├── State.scala             # Raft state management
├── StateMachine.scala      # State machine abstraction
├── Types.scala             # Core domain types
├── RPC.scala              # RPC abstraction
├── LogStore.scala         # Log storage abstraction
└── SnapshotStore.scala    # Snapshot storage abstraction
```

## Constitution Compliance
All code changes must adhere to ZIO Raft Constitution v1.0.0:
- **Functional Purity**: Immutable data, explicit effects, no unsafe operations
- **Error Handling**: ZIO error channel, no exceptions for business logic
- **Code Preservation**: Extend existing abstractions, maintain backward compatibility
- **ZIO Consistency**: Use ZIO primitives for concurrency, streaming, and resources
- **Testing**: TDD approach with comprehensive test coverage

## Code Style
**Scala/ZIO Specific**:
- Use ZIO effect types (UIO, URIO, ZIO) for all side effects
- Immutable case classes for data models
- Explicit error types in ZIO error channel
- ZStream for streaming operations
- ZLayer for dependency injection
- Functional programming patterns (no var, mutable collections)

## Recent Changes
[LAST 3 FEATURES AND WHAT THEY ADDED]

<!-- MANUAL ADDITIONS START -->
<!-- MANUAL ADDITIONS END -->