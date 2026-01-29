# Cross-Language Protocol Compatibility Fixtures

This directory contains hex-encoded protocol message fixtures used for cross-language compatibility testing between Scala and TypeScript implementations.

## Purpose

These fixtures ensure that Scala (server/protocol) and TypeScript (client) encode and decode messages identically. **Scala is the source of truth** - these files define the expected binary format.

## Structure

Each file contains a single hex string representing one protocol message:

```
CreateSession.hex       - Client message: Create new session
ContinueSession.hex     - Client message: Continue existing session
KeepAlive.hex          - Client message: Keep session alive
ClientRequest.hex      - Client message: Submit client request
Query.hex              - Client message: Submit query
ServerRequestAck.hex   - Client message: Acknowledge server request
CloseSession.hex       - Client message: Close session
ConnectionClosed.hex   - Client message: Connection closed

SessionCreated.hex         - Server message: Session created
SessionContinued.hex       - Server message: Session continued
SessionRejected.hex        - Server message: Session rejected (with leader)
SessionRejectedNoLeader.hex - Server message: Session rejected (no leader)
SessionClosed.hex          - Server message: Session closed
KeepAliveResponse.hex      - Server message: Keep-alive response
ClientResponse.hex         - Server message: Client response
QueryResponse.hex          - Server message: Query response
ServerRequest.hex          - Server message: Server request
RequestError.hex           - Server message: Request error
```

## Usage

### Scala Tests
Read fixtures using `Source.fromResource`:
```scala
private def readFixture(filename: String): String =
  Source.fromResource(s"fixtures/$filename").mkString.trim

val expectedHex = readFixture("CreateSession.hex")
```

### TypeScript Tests
Read fixtures from the Scala module using relative paths:
```typescript
import { readFileSync } from 'fs';
import { join } from 'path';

function readFixture(filename: string): string {
  const fixturePath = join(__dirname, '../../../..', 'client-server-protocol', 
    'src', 'test', 'resources', 'fixtures', filename);
  return readFileSync(fixturePath, 'utf8').trim();
}

const expectedHex = readFixture('CreateSession.hex');
```

## Updating Fixtures

When protocol changes require fixture updates:

1. Run Scala tests - they will fail showing actual vs expected hex
2. Verify the change is intentional (not a bug)
3. Manually update the corresponding `.hex` file with the new value
4. Both Scala and TypeScript tests should now pass

**Do not** automate fixture generation - manual updates force engineers to review protocol changes.

## Test Data

All fixtures use consistent test data defined in `ClientCompatibilitySpec.scala`:
- Timestamp: `1705680000000` (2024-01-19 12:00:00 UTC)
- SessionId: `"test-session-123"`
- MemberId: `"node-1"`
- Nonce: `42`
- RequestId: `100`
- CorrelationId: `"corr-123"`
- Payload: `"test-payload"` (UTF-8)
- Capabilities: `{"capability1": "value1", "capability2": "value2"}`
