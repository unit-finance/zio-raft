# Final Fix: Added lastSentBefore Parameter to GetRequestsForRetry

## Issue
Missing `lastSentBefore` field in `GetRequestsForRetry` command.

## Comment Location
Line 110 in `SessionCommand.scala`

## Fix Applied

### SessionCommand.scala

**Before**:
```scala
case class GetRequestsForRetry(
  sessionId: SessionId,
  currentTime: java.time.Instant
) extends SessionCommand[Nothing]:
  type Response = List[PendingServerRequest[Any]]
```

**After**:
```scala
case class GetRequestsForRetry(
  sessionId: SessionId,
  lastSentBefore: java.time.Instant,  // NEW - threshold for retry eligibility
  currentTime: java.time.Instant
) extends SessionCommand[Nothing]:
  type Response = List[PendingServerRequest[Any]]
```

## Purpose of lastSentBefore

The `lastSentBefore` parameter serves as a **retry threshold**:
- Only return server requests where `lastSentAt < lastSentBefore`
- Allows caller to specify the time cutoff for which requests need retry
- Example: "Return requests not sent in the last 5 minutes"
  - `lastSentBefore = now - 5.minutes`
  - `currentTime = now` (for updating lastSentAt on returned requests)

## Usage Pattern

```scala
val retryThreshold = currentTime.minus(Duration.ofMinutes(5))

val cmd = SessionCommand.GetRequestsForRetry(
  sessionId = SessionId("session-123"),
  lastSentBefore = retryThreshold,  // Only requests older than this
  currentTime = currentTime          // Update lastSentAt to this
)
```

The state machine would:
1. Filter pending requests: `req.lastSentAt < lastSentBefore`
2. Update those requests: `req.copy(lastSentAt = currentTime)`
3. Return the filtered and updated list

## Files Modified

1. ✅ `session-state-machine/src/main/scala/zio/raft/sessionstatemachine/SessionCommand.scala`
   - Added `lastSentBefore` parameter
   - Updated documentation

2. ✅ `session-state-machine/src/test/scala/zio/raft/sessionstatemachine/SessionCommandSpec.scala`
   - Updated test to verify all 3 fields
   - Updated pattern matching test with both parameters

## Implementation Note

The actual filtering logic in `handleGetRequestsForRetry` is currently unimplemented (placeholder returning empty list) due to HMap iteration limitations. However, the command now has the correct signature for when it's properly implemented.

When implemented, the logic would be:
```scala
private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry): ... =
  State.modify { state =>
    val pending = getPendingServerRequests(state, cmd.sessionId)
    
    // Filter by lastSentBefore threshold
    val eligible = pending.filter(req => req.lastSentAt.isBefore(cmd.lastSentBefore))
    
    // Update lastSentAt to currentTime
    val updated = eligible.map(_.copy(lastSentAt = cmd.currentTime))
    
    // Update state with new lastSentAt values
    val newState = updated.foldLeft(state) { (s, req) =>
      s.updated["serverRequests"](makeKey(req), req)
    }
    
    (newState, updated)
  }
```

## Summary

✅ **Fixed**: Added missing `lastSentBefore` parameter to `GetRequestsForRetry`
- Provides retry threshold for filtering requests
- Separate from `currentTime` which is used for updating timestamps
- Tests updated to verify both parameters
- Ready for implementation when HMap iteration is available
