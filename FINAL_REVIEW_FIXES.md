# Final Review Fixes - Capabilities and Abstract Method

## Issues Fixed

### 1. ✅ **Add capabilities parameter to handleSessionExpired**

**RationaleMenuUser state machine needs the session capabilities to properly clean up session-specific state when a session expires.

**Changes**:

1. **Abstract method signature updated**:
   ```scala
   // Before
   protected def handleSessionExpired(sessionId: SessionId): State[...]
   
   // After
   protected def handleSessionExpired(
     sessionId: SessionId, 
     capabilities: Map[String, String]
   ): State[...]
   ```

2. **Internal handler updated to retrieve capabilities from metadata**:
   ```scala
   private def handleSessionExpired_internal(cmd: SessionCommand.SessionExpired): State[...] =
     State.modify { state =>
       // Get capabilities from metadata before expiring
       val capabilities = state.get["metadata"](SessionId.unwrap(cmd.sessionId))
         .map(_.capabilities)
         .getOrElse(Map.empty[String, String])
       
       // Call user's handler with capabilities
       val (newState, serverRequests) = handleSessionExpired(cmd.sessionId, capabilities).run(state)
       
       // Remove all session data
       val finalState = expireSession(newState, cmd.sessionId)
       
       (finalState, serverRequests)
     }
   ```

3. **All test implementations updated**:
   - `SessionStateMachineTemplateSpec.scala`
   - `IdempotencySpec.scala`
   - `ResponseCachingSpec.scala`
   - `CumulativeAckSpec.scala`
   - `StateNarrowingSpec.scala`
   - `InvariantSpec.scala`
   
   All now accept capabilities parameter (currently ignored in tests).

### 2. ✅ **Make shouldTakeSnapshot abstract**

**RationaleMenuSnapshot policy should be defined by users, not hardcoded in the base class.

**Changes**:

1. **Method made abstract**:
   ```scala
   // Before (with default implementation)
   def shouldTakeSnapshot(...): Boolean =
     (commitIndex.value - lastSnapshotIndex.value) >= 1000
   
   // After (abstract - no implementation)
   def shouldTakeSnapshot(
     lastSnapshotIndex: Index, 
     lastSnapshotSize: Long, 
     commitIndex: Index
   ): Boolean
   ```

2. **All test implementations added**:
   All 6 test classes now implement:
   ```scala
   def shouldTakeSnapshot(
     lastSnapshotIndex: zio.raft.Index, 
     lastSnapshotSize: Long, 
     commitIndex: zio.raft.Index
   ): Boolean = false  // Don't take snapshots in tests
   ```

## Summary of Abstract Methods

Users extending `SessionStateMachine` must now implement **5 abstract methods**:

```scala
abstract class SessionStateMachine[UC, SR, UserSchema] extends StateMachine[...]:
  
  // 1. Command processing
  protected def applyCommand(command: UC): State[...]
  
  // 2. Session creation
  protected def handleSessionCreated(sessionId: SessionId, capabilities: Map[String, String]): State[...]
  
  // 3. Session expiration (NOW with capabilities)
  protected def handleSessionExpired(sessionId: SessionId, capabilities: Map[String, String]): State[...]
  
  // 4. Snapshot creation
  def takeSnapshot(state: HMap[CombinedSchema[UserSchema]]): Stream[Nothing, Byte]
  
  // 5. Snapshot restoration
  def restoreFromSnapshot(stream: Stream[Nothing, Byte]): UIO[HMap[CombinedSchema[UserSchema]]]
  
  // 6. Snapshot policy (NOW abstract)
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean
```

## Files Modified

### Source Code
1. `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionStateMachine.scala`
   - Updated `handleSessionExpired` signature (added capabilities)
   - Updated `handleSessionExpired_internal` to retrieve and pass capabilities
   - Made `shouldTakeSnapshot` abstract

### Tests (6 files)
All test implementations updated via sed + manual additions:
1. `SessionStateMachineTemplateSpec.scala`
2. `IdempotencySpec.scala`
3. `ResponseCachingSpec.scala`
4. `CumulativeAckSpec.scala`
5. `StateNarrowingSpec.scala`
6. `InvariantSpec.scala`

Each now:
- Accepts capabilities in `handleSessionExpired`
- Implements `shouldTakeSnapshot` (returns false for tests)

## Impact

### Breaking Changes
- `handleSessionExpired` signature changed - all implementations must add capabilities parameter
- `shouldTakeSnapshot` must be implemented by all users (no default)

### Benefits
1. **Session expiration logic**: Users can access session capabilities during cleanup
2. **Flexible snapshot policy**: Each user can define their own strategy
3. **No hidden defaults**: Snapshot behavior is explicit

## Example Usage

```scala
class MyStateMachine extends SessionStateMachine[MyCmd, MySR, MySchema]:
  
  protected def handleSessionExpired(
    sessionId: SessionId, 
    capabilities: Map[String, String]
  ): State[HMap[CombinedSchema[MySchema]], List[MySR]] =
    State.modify { state =>
      // Can use capabilities to determine cleanup strategy
      if capabilities.contains("premium") then
        // Send premium user notification
        (state, List(PremiumUserExpiredNotification(sessionId)))
      else
        (state, Nil)
    }
  
  def shouldTakeSnapshot(lastSnapshotIndex: Index, lastSnapshotSize: Long, commitIndex: Index): Boolean =
    // Custom policy - take snapshot every 5000 entries or when size > 10MB
    (commitIndex.value - lastSnapshotIndex.value) >= 5000 || lastSnapshotSize > 10_000_000
```

---

**All requested fixes completed!**
