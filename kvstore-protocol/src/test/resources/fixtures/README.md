# Cross-Language Protocol Compatibility Fixtures

This directory contains hex-encoded protocol message fixtures used for cross-language compatibility testing between Scala and TypeScript implementations.

## Purpose

These fixtures ensure that Scala (server/protocol) and TypeScript (client) encode and decode messages identically. **Scala is the source of truth** - these files define the expected binary format.

## Structure

Each file contains a single hex string representing one KVStore protocol message:

```
Set.hex           - Client request: Set key-value pair
Get.hex           - Query: Get value by key
Watch.hex         - Client request: Watch key for changes
Notification.hex  - Server request: Notify client of value change
```

## Usage

### Scala Tests
Read fixtures using `Source.fromResource`:
```scala
private def readFixture(filename: String): String =
  Source.fromResource(s"fixtures/$filename").mkString.trim

val expectedHex = readFixture("Set.hex")
```

### TypeScript Tests
Read fixtures from the Scala module using relative paths:
```typescript
import { readFileSync } from 'fs';
import { join } from 'path';

function readFixture(filename: string): string {
  const fixturePath = join(__dirname, '../..', '..', 'kvstore-protocol', 
    'src', 'test', 'resources', 'fixtures', filename);
  return readFileSync(fixturePath, 'utf8').trim();
}

const expectedHex = readFixture('Set.hex');
```

## Updating Fixtures

When protocol changes require fixture updates:

1. Run Scala tests - they will fail showing actual vs expected hex
2. Verify the change is intentional (not a bug)
3. Manually update the corresponding `.hex` file with the new value
4. Both Scala and TypeScript tests should now pass

**Do not** automate fixture generation - manual updates force engineers to review protocol changes.

## Test Data

All fixtures use consistent test data:
- Key: `"test-key"`
- Value: `"test-value"`
