# UD-11816: Raft Read Commands - Subtask Breakdown

## Overview
Implementation of Linearizable Reads in Raft via Heartbeat Validation and Pending Reads Queue

**Parent Task:** [UD-11816](https://unit-finance.atlassian.net/browse/UD-11816)

## Subtasks

### 1. Implement Pending Reads Queue data structure
**Status:** 🔲 Not Started  
**Priority:** High  
**Estimated Effort:** 2-3 days

**Description:** Create the `pendingReads` queue data structure in the Raft leader

**Requirements:**
- Add `pendingReads` field to Raft
- Each pending read should be annotated with the current `lastLogIndex` when enqueued
- Reads do not increment the log index; they are passively tracked by metadata
- Implement basic operations: enqueue, dequeue, and filtering by log index

**Implementation Details:**
- Define data structure for pending read entries
- Add to Raft state management
- Ensure thread-safe operations

**Files to modify:**
- `raft/src/main/scala/zio/raft/State.scala`
- `raft/src/main/scala/zio/raft/Types.scala`

---

### 2. Implement readState method with promise-based waiting
**Status:** 🔲 Not Started  
**Priority:** High  
**Estimated Effort:** 3-4 days

**Description:** Implement the `readState` method that adds read commands to the pending reads queue and waits on promises before returning

**Requirements:**
- Add `readState` method to Raft interface
- Create promise for each read operation
- Enqueue read with current `lastLogIndex`
- Wait on promise before returning result
- Handle timeout and error cases

**Implementation Details:**
- Use ZIO Promise for async coordination
- Ensure proper error handling and cleanup
- Add appropriate logging for debugging

**Files to modify:**
- `raft/src/main/scala/zio/raft/Raft.scala`
- `raft/src/main/scala/zio/raft/State.scala`

**Dependencies:** Subtask 1 (Pending Reads Queue)

---

### 3. Implement Write Completion Check in applyToStateMachine
**Status:** 🔲 Not Started  
**Priority:** High  
**Estimated Effort:** 2-3 days

**Description:** Add logic in `applyToStateMachine` to check and complete pending reads after state is applied

**Requirements:**
- When a write command is committed and applied, check the `pendingReads` queue
- Complete all pending reads whose associated log index is ≤ current `appliedIndex`
- Remove completed reads from the queue
- Fulfill promises with the current state

**Implementation Details:**
- Integrate with existing `applyToStateMachine` method
- Ensure atomic operations for queue management
- Handle edge cases and error conditions

**Files to modify:**
- `raft/src/main/scala/zio/raft/Raft.scala`
- `raft/src/main/scala/zio/raft/StateMachine.scala`

**Dependencies:** Subtask 1 (Pending Reads Queue)

---

### 4. Add HeartbeatDue validation for read operations
**Status:** 🔲 Not Started  
**Priority:** High  
**Estimated Effort:** 2-3 days

**Description:** Implement leadership validation using the existing `HeartbeatDue` mechanism before accepting reads

**Requirements:**
- Before accepting a read, perform leadership validation
- If recent heartbeat quorum has been received, no additional heartbeat needed
- Only send new heartbeats if `HeartbeatDue` indicates majority hasn't been seen recently
- Reject reads if leadership validation fails

**Implementation Details:**
- Integrate with existing `HeartbeatDue` logic
- Add appropriate error handling for leadership failures
- Ensure minimal performance impact

**Files to modify:**
- `raft/src/main/scala/zio/raft/Raft.scala`
- `raft/src/main/scala/zio/raft/HeartbeatDue.scala`

**Dependencies:** Subtask 2 (readState method)

---

### 5. Implement No-Op Entry on Leader Election
**Status:** 🔲 Not Started  
**Priority:** Medium  
**Estimated Effort:** 1-2 days

**Description:** Add no-op log entry when a node becomes leader to ensure Leader Completeness Property

**Requirements:**
- When node becomes leader, append and commit a no-op log entry
- Ensures leader knows which entries are committed
- Satisfies Leader Completeness Property from Raft algorithm
- Should happen automatically during leader election

**Implementation Details:**
- Integrate with existing leader election logic
- Add no-op entry type to log system
- Ensure proper commitment and replication

**Files to modify:**
- `raft/src/main/scala/zio/raft/Raft.scala`
- `raft/src/main/scala/zio/raft/Types.scala`

**Dependencies:** None (can be implemented independently)

---

### 6. Add Dirty Reads support (Optional)
**Status:** 🔲 Not Started  
**Priority:** Low  
**Estimated Effort:** 1-2 days

**Description:** Support explicitly flagged "dirty reads" that bypass leadership and apply-order guarantees

**Requirements:**
- Add dirty read flag to read operations
- Bypass leadership validation for dirty reads
- Skip pending reads queue for dirty reads
- Return immediate state without waiting for writes
- Use for best-effort performance in non-critical paths

**Implementation Details:**
- Add optional dirty read parameter to readState
- Implement fast path for dirty reads
- Add appropriate warnings/documentation about consistency

**Files to modify:**
- `raft/src/main/scala/zio/raft/Raft.scala`

**Dependencies:** Subtask 2 (readState method)

---

### 7. Add comprehensive unit tests for linearizable reads
**Status:** 🔲 Not Started  
**Priority:** High  
**Estimated Effort:** 3-4 days

**Description:** Create comprehensive unit tests to verify all linearizable read functionality

**Requirements:**
- Test read-after-write ordering
- Test no-op on leadership change
- Test leadership check behavior (with and without new heartbeats)
- Test dirty vs. strict reads
- Test pending reads completion
- Test error scenarios and edge cases

**Implementation Details:**
- Use existing test framework patterns
- Add integration tests with mock RPC
- Test concurrent read/write scenarios
- Verify proper cleanup and error handling

**Files to create/modify:**
- `raft/src/test/scala/zio/raft/LinearizableReadsSpec.scala`
- `raft/src/test/scala/zio/raft/RaftSpec.scala` (extend existing)

**Dependencies:** Subtasks 1-6 (all implementation tasks)

---

## Implementation Order

### Phase 1: Foundation (High Priority)
1. **Pending Reads Queue** (foundation for all read operations)
2. **readState method** (core functionality)
3. **Write Completion Check** (completion logic)

### Phase 2: Leadership & Validation (High Priority)
4. **HeartbeatDue validation** (leadership validation)
5. **No-Op Entry** (leader election enhancement)

### Phase 3: Testing & Optimization (Medium Priority)
6. **Unit Tests** (verification of all functionality)
7. **Dirty Reads** (optional optimization)

## Acceptance Criteria
- [ ] Reads are returned only after all writes prior to the read (based on log index) have been applied
- [ ] `pendingReads` are completed once the corresponding index is applied
- [ ] Leadership is validated using the existing `HeartbeatDue` logic, with no new messages sent if recent heartbeats suffice
- [ ] A no-op entry is committed at the beginning of a new leader term
- [ ] Optional: dirty reads can be issued without full validation
- [ ] Unit tests verify:
  - [ ] Read-after-write ordering
  - [ ] No-op on leadership change
  - [ ] Leadership check behavior (with and without new heartbeats)
  - [ ] Dirty vs. strict reads

## Notes
- Each subtask should be committed separately
- Consider performance implications of each implementation
- Ensure backward compatibility with existing Raft functionality
- Add appropriate logging for debugging and monitoring

## Progress Tracking
- **Started:** [Date]
- **Completed:** [Date]
- **Total Estimated Effort:** 14-21 days
- **Actual Effort:** [TBD] 