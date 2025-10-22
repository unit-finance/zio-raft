# ALL PR COMMENTS COMPREHENSIVE CHECKLIST

## SOURCE CODE COMMENTS (session-state-machine/src/)

### SessionCommand.scala

| Line | Comment | Status | Notes |
|------|---------|--------|-------|
| 41 | missing the lowest Request id field | ✅ DONE | Added `lowestRequestId: RequestId` |
| 69 | this should be called CreateSession | ✅ DONE | Renamed from SessionCreationConfirmed |
| 43 | it should be list of ServerRequest, not any | ✅ DONE | Changed to `List[SR]` with SR type param |
| 75 | List of ServerRequest, not any | ✅ DONE | Changed CreateSession response to `List[SR]` |
| 91 | List of ServerRequest, not any | ✅ DONE | Changed SessionExpired response to `List[SR]` |
| 106 | this should also include a time | ❌ TODO | Add createdAt timestamp to GetRequestsForRetry? |
| 112 | instead of current time, let's use lastSentBefore | ✅ DONE | Added `lastSentBefore` parameter |
| 112 | actually we need both, what to retry and what should be the lastSentAt | ✅ DONE | Has both `lastSentBefore` and `currentTime` |
| 112 | let's call this createdAt, and move it to be the first parameter | ❌ TODO | Rename currentTime to createdAt? Move to first param? |

### SessionMetadata.scala

| Line | Comment | Status | Notes |
|------|---------|--------|-------|
| 21 | this is part of the key, it's a waste of memory here | ✅ DONE | Removed `sessionId` field |

### SessionStateMachine.scala

| Line | Comment | Status | Notes |
|------|---------|--------|-------|
| 111 | should also take capabilities | ✅ DONE | `handleSessionExpired` takes capabilities |
| 178 | let's leave this unimplemented | ✅ DONE | `handleGetRequestsForRetry` returns empty list |
| 196 | cachedResponse should be of Response only | ✅ DONE | Only caches response, not server requests |
| 210 | we should not cache the server requests, only the response | ✅ DONE | Cache stores only response |
| 212 | you are not removing cache entries according to lowerRequestId | ❌ TODO | Implement `cleanupCache` logic |
| 221 | so add timestamp to the command and use it! | ❌ TODO | Add createdAt to which command? |
| 222 | use State.update instead of modify if return type is Unit | ✅ DONE | `handleServerRequestAck` uses `State.update` |
| 250 | fix this, add `createdAt` to all session commands! | ❌ TODO | Add createdAt field to ALL commands |
| 290 | we only need to retry requests that were sent before specific time | ✅ DONE | Using `lastSentBefore` parameter |
| 306 | implement this! you have everything you need | ❌ TODO | Implement `handleGetRequestsForRetry` properly |
| 325 | addServerRequests should accept the createdAt of the command | ✅ DONE | Has `createdAt` parameter |
| 330 | move to be the first parameter | ❌ TODO | Move createdAt to first param in addServerRequests |
| 472 | let's rename this to lastSentBefore, as it is not now... | ❌ TODO | Check hasPendingRequests parameter name |

### package.scala

| Line | Comment | Status | Notes |
|------|---------|--------|-------|
| 36 | It should be List[PendingServerRequest[?]], key is session id | ✅ DONE | Changed to `List[PendingServerRequest[?]]` |

## DOCUMENTATION COMMENTS (specs/002-session-state-machine/)

### data-model.md
- Line 9: Drop "This is NOT about..." - keep what it IS about
- Line 65: C is subtype of raft Command, we don't need R
- Line 77: instead of R use command.Response
- Line 106: let's pass on the capabilities here as well
- Line 127: return the server requests as well
- Line 134: this is not the command, the command is CreateSession
- Line 139: should also return list of server requests
- Line 142: we should also remove from cache (lowest sequence number protocol)
- Line 148: should return empty list of server requests (not caching them)
- Line 264: should be `session/serverRequests/{sessionId} → List[PendingServerRequest[SR]]`
- Line 367: missing "lowest sequence number protocol"
- Line 441: session id is waste of memory (it's the key)
- Line 492: the command is CreateSession, fix across entire spec
- Line 503: GetServerRequestsForRetry suggestion
- Line 542: need to wrap user response with SessionResponse
- Line 544: we don't need that, remove
- Line 561: remove this, we don't need this class
- Line 577: should accept SR, payload should be SR

### plan.md
- Line 135: "this is bullshit, remove this section"
- Line 135: "We have one library and one main type"
- Line 226: types are not up to date, update according to data model
- Line 229: we are not creating any codecs

### ARCHITECTURE_FINAL.md
- Line 127: We don't need Command, it's part of Request as `type Response`
- Line 161: check core library Command trait, Response is a type

### contracts/session-state-machine.md
- Line 1: not up to date with changes in data-model

### research.md
- Line 90: add hasPendingRequests as public method

## CRITICAL MISSING ITEMS

### 1. Add `createdAt: Instant` to ALL SessionCommand case classes

ALL commands need `createdAt` as the FIRST parameter:

```scala
case class ClientRequest[UC <: Command, SR](
  createdAt: Instant,        // ADD THIS FIRST
  sessionId: SessionId,
  requestId: RequestId,
  lowestRequestId: RequestId,
  command: UC
)

case class ServerRequestAck[SR](
  createdAt: Instant,        // ADD THIS
  sessionId: SessionId,
  requestId: RequestId
)

case class CreateSession[SR](
  createdAt: Instant,        // ADD THIS
  sessionId: SessionId,
  capabilities: Map[String, String]
)

case class SessionExpired[SR](
  createdAt: Instant,        // ADD THIS
  sessionId: SessionId
)

case class GetRequestsForRetry[SR](
  createdAt: Instant,        // ADD THIS (rename currentTime?)
  sessionId: SessionId,
  lastSentBefore: Instant
)
```

### 2. Move `createdAt` to first parameter in `addServerRequests`

```scala
private def addServerRequests(
  createdAt: Instant,        // MOVE TO FIRST
  state: HMap[...],
  sessionId: SessionId,
  serverRequests: List[SR]
): (HMap[...], List[SR]) =
```

### 3. Implement cleanupCache based on lowestRequestId

In `handleClientRequest`, implement cache cleanup:
```scala
private def cleanupCache(
  state: HMap[CombinedSchema[UserSchema]],
  sessionId: SessionId,
  lowestRequestId: RequestId
): HMap[CombinedSchema[UserSchema]] =
  // Remove all cache entries for this session where requestId < lowestRequestId
  // Challenge: HMap doesn't support iteration
  // Need either:
  // 1. Access to internal map
  // 2. Track request IDs separately
  // 3. HMap.keys() method
```

### 4. Implement handleGetRequestsForRetry properly

Since we now store List[PendingServerRequest[SR]] per session:
```scala
private def handleGetRequestsForRetry(cmd: SessionCommand.GetRequestsForRetry[SR]): State[...] =
  State.modify { state =>
    val pending = state.get["serverRequests"](SessionId.unwrap(cmd.sessionId))
      .getOrElse(List.empty)
    
    // Filter by lastSentBefore
    val eligible = pending.filter(req => req.lastSentAt.isBefore(cmd.lastSentBefore))
    
    // Update lastSentAt to createdAt (from command)
    val updated = eligible.map(_.copy(lastSentAt = cmd.createdAt))
    
    // Save updated list
    val remaining = pending.filterNot(eligible.contains)
    val newList = remaining ++ updated
    val newState = state.updated["serverRequests"](SessionId.unwrap(cmd.sessionId), newList)
    
    (newState, updated)
  }
```

### 5. Update hasPendingRequests parameter

Line 472 comment suggests renaming the third parameter from `retryThreshold: Instant` to `lastSentBefore: Instant` for consistency.

### 6. Fix all test files

All test files need SR type parameter added to SessionCommand constructors.

---

## PRIORITY ORDER

1. ✅ Add SR type parameter to SessionCommand and all case classes
2. ❌ Add `createdAt: Instant` as FIRST parameter to ALL SessionCommand case classes
3. ❌ Move `createdAt` to first parameter in `addServerRequests`
4. ❌ Update all command handlers to extract and use `createdAt`
5. ❌ Implement `cleanupCache` (if possible with current HMap API)
6. ❌ Implement `handleGetRequestsForRetry` properly
7. ❌ Rename parameter in `hasPendingRequests` to `lastSentBefore`
8. ❌ Fix all test files
9. ❌ Update documentation files (if needed)
