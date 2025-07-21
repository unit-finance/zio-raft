# ReadState Implementation Plan

## Overview
Implementation plan for linearizable reads in ZIO Raft using internal read commands and state-based completion.

## Problem Identified
The initial `PendingReadEntry[A <: Command]` approach had a fundamental flaw:
- `Command` trait is designed for write operations that modify state
- Read commands should not go through the state machine or modify state
- Read commands only need to return the current state at a consistent point in time

## Solution Design

### Core Principles
1. **Internal Read Commands**: Create read commands that are internal to Raft implementation
2. **No Serialization**: Read commands are leader-local and ephemeral
3. **State-based Completion**: Return the actual state after all previous writes applied
4. **Clean External API**: Simple `readState` method that returns the state

### Type Design

#### Internal Read Command (Raft-only)
```scala
private case class InternalReadCommand[S](
  promise: Promise[NotALeaderError, S]
)

case class PendingReadEntry[S](
  promise: Promise[NotALeaderError, S],
  enqueuedAtIndex: Index,
  timestamp: Instant
)

case class PendingReads[S](entries: List[PendingReadEntry[S]]):
  def enqueue(entry: PendingReadEntry[S]): PendingReads[S]
  def dequeue(upToIndex: Index): (List[PendingReadEntry[S]], PendingReads[S])
  def complete(upToIndex: Index, state: S): PendingReads[S]
```

#### Updated State Types
```scala
case class Leader[S](
  nextIndex: NextIndex,
  matchIndex: MatchIndex,
  heartbeatDue: HeartbeatDue,
  replicationStatus: ReplicationStatus,
  commitIndex: Index,
  lastApplied: Index,
  pendingReads: PendingReads[S]
)
```

#### Public API
```scala
def readState: ZIO[Any, NotALeaderError, S]
```

## Implementation Steps

### 1. Update Type Definitions
- [ ] Update `PendingReadEntry[S]` and `PendingReads[S]` in `Types.scala`
- [ ] Change `Leader` to `Leader[S]` in `State.scala`
- [ ] Add `complete` method to `PendingReads[S]`

### 2. Update Generic Type Parameters
- [ ] Update `State` to be generic over `S`
- [ ] Update `Raft[S, A]` usage throughout codebase
- [ ] Fix all type signatures and pattern matches

### 3. Implement readState Method
- [ ] Add `readState` method to `Raft[S, A]`
- [ ] Implement leadership validation
- [ ] Create internal read command and enqueue it
- [ ] Return promise that resolves when index is applied

### 4. Update applyToStateMachine
- [ ] After applying write commands and updating state
- [ ] Complete pending reads with the NEW state
- [ ] Use `pendingReads.complete(upToIndex, newState)`

### 5. Update Test Code
- [ ] Fix all pattern matches for `Leader[S]`
- [ ] Ensure all tests compile and pass

## Key Design Decisions

### 1. State Access Pattern
**Decision**: Complete reads with state *after* write commands are applied
**Rationale**: Ensures linearizability - reads see all previous writes

### 2. Type Safety
**Decision**: Make `Leader[S]` generic over state type
**Rationale**: Compile-time guarantee that reads return correct state type

### 3. Simplicity
**Decision**: Drop complex command hierarchies, just return state
**Rationale**: Let users run their own read-only operations on returned state

### 4. Internal vs External
**Decision**: Read commands are internal implementation details
**Rationale**: Clean external API, no serialization needed

## Benefits

1. **🔒 Encapsulation**: ReadCommand is internal, users don't see it
2. **🚀 Performance**: No serialization overhead
3. **🎯 Simplicity**: Clean external API - just return state
4. **🔄 Resilience**: Natural retry pattern on timeout
5. **📏 Type Safety**: Compile-time guarantees about state type
6. **⚡ Efficiency**: Direct state access, no unnecessary abstractions

## Implementation Notes

- Read commands are NOT stored in the log
- Read commands are NOT serialized
- Read commands timeout and client retries on another node
- Reads complete with the state after all previous writes applied
- Users can run their own ReadOnlyStateMachine on returned state

## Files to Modify

1. `raft/src/main/scala/zio/raft/Types.scala` - Update type definitions
2. `raft/src/main/scala/zio/raft/State.scala` - Make Leader generic
3. `raft/src/main/scala/zio/raft/Raft.scala` - Add readState method, update applyToStateMachine
4. `raft/src/test/scala/zio/raft/RaftSpec.scala` - Fix pattern matches
5. All other files with Leader pattern matches or type references

## Success Criteria

- [ ] All code compiles successfully
- [ ] All existing tests pass
- [ ] `readState` method returns state after all previous writes applied
- [ ] Read commands are internal and not serialized
- [ ] Type safety maintained throughout 