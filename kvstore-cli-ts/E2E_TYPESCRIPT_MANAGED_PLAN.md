# E2E Testing with TypeScript-Managed Server - Implementation Plan

**Author:** AI Assistant  
**Date:** 2026-01-29  
**Status:** 🔴 Proposed

## Overview

This plan outlines the implementation of a comprehensive E2E testing infrastructure where the TypeScript test suite manages the lifecycle of the KVStore server. This approach provides the best developer experience while enabling thorough failure scenario testing without requiring Docker.

## Goals

1. **Single Command Testing**: `npm run test:e2e` starts server, runs tests, cleans up
2. **Automatic Cleanup**: Guaranteed server shutdown even on test failures
3. **Failure Testing**: Ability to stop/kill/restart server mid-test
4. **CI-Friendly**: Minimal CI configuration, standard Node.js workflow
5. **Developer Experience**: No manual server management, no external scripts
6. **Progressive Enhancement**: Start simple, add complexity as needed
7. **Automate Manual Tests**: Implement all test scenarios from `MANUAL_TEST_PLAN_SERVER_CONTROL.md`

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Vitest Test Runner                       │
├─────────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Basic Tests  │  │Failure Tests │  │ Load Tests   │     │
│  │ (shared srv) │  │(per-test srv)│  │ (shared srv) │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                  │                  │              │
│         └──────────────────┼──────────────────┘              │
│                            │                                 │
├────────────────────────────┼─────────────────────────────────┤
│                   ServerManager                              │
│  ┌────────────────────────────────────────────────────┐     │
│  │ • Spawn SBT process                                │     │
│  │ • Wait for server ready (port check)              │     │
│  │ • Capture logs                                     │     │
│  │ • Graceful/forceful shutdown                       │     │
│  │ • Restart support                                  │     │
│  └────────────────────────────────────────────────────┘     │
├─────────────────────────────────────────────────────────────┤
│                       Child Process                         │
│                    (SBT + KVStore Server)                   │
└─────────────────────────────────────────────────────────────┘
```

## Test Coverage

This plan automates the test scenarios defined in `MANUAL_TEST_PLAN_SERVER_CONTROL.md`:

| Test ID | Scenario | Priority | Phase | Status |
|---------|----------|----------|-------|--------|
| Test 13 | Server Not Running | Critical | Phase 3 | ⏳ Planned |
| Test 14 | Server Crash Mid-Operation | Critical | Phase 3 | ⏳ Planned |
| Test 15 | Watch During Server Restart | Critical | Phase 3 | ⏳ Planned |
| Test 17 | Graceful Shutdown During Watch | Critical | Phase 3 | ⏳ Planned |
| Test 19 | Multiple Clients Competing | Important | Phase 3 | ⏳ Planned |
| Test 21 | Load Test (50 rapid connections) | Important | Phase 3 | ⏳ Planned |
| Test 16 | Session Expiry | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 18 | Network Partition | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 20 | Registration Race Condition | Nice-to-have | Phase 5 | 📋 Backlog |
| Test 22 | Server Logs Validation | Nice-to-have | Phase 5 | 📋 Backlog |

**Phase 3 Target:** 6/10 tests (all Critical + Important)  
**Phase 5 Target:** 10/10 tests (complete coverage)

---

## Implementation Phases

### Phase 1: Core Server Manager Infrastructure

**Goal:** Build reliable server lifecycle management

**Tasks:**

1. **Create `serverManager.ts` helper** (`tests/helpers/serverManager.ts`)
   - `KVStoreServer` class with lifecycle methods:
     - `start()`: Spawn SBT process, wait for ready
     - `stop()`: Graceful SIGTERM shutdown
     - `kill()`: Forceful SIGKILL
     - `restart()`: Stop + Start
     - `isRunning()`: Health check
   - Port availability checking via TCP socket
   - Log capture for debugging
   - Configurable timeouts

2. **Global server management**
   - `startGlobalServer()`: Singleton server for fast tests
   - `stopGlobalServer()`: Cleanup global server
   - `getGlobalServer()`: Access global instance

3. **Testing the manager**
   - Manual smoke test: Start/stop/restart
   - Verify cleanup works on Ctrl+C
   - Test timeout scenarios

**Success Criteria:**
- [ ] Server starts reliably within 60 seconds
- [ ] Port check detects when server is ready
- [ ] Graceful shutdown works (no orphaned processes)
- [ ] Logs are captured and shown on failure
- [ ] Works on macOS (primary), Linux (CI)

**Estimated Effort:** 4-6 hours

---

### Phase 2: Migrate Basic E2E Tests

**Goal:** Convert existing e2e tests to use TypeScript-managed server

**Tasks:**

1. **Update `cli.test.ts`**
   - Add `beforeAll` hook to start global server
   - Add `afterAll` hook to stop global server
   - Update timeouts to account for server startup
   - Remove manual server prerequisite from docs

2. **Update `run-e2e.sh`** (keep for backwards compatibility)
   - Option 1: Keep old behavior (expect running server)
   - Option 2: Deprecate in favor of `npm run test:e2e`

3. **Add npm scripts** (package.json)
   ```json
   "test:e2e": "vitest run tests/e2e/cli.test.ts",
   "test:e2e:watch": "vitest watch tests/e2e/cli.test.ts",
   "test:e2e:debug": "DEBUG_SERVER=1 vitest run tests/e2e/cli.test.ts"
   ```

4. **Pre-compile optimization**
   - Add helper script to pre-compile server: `npm run pretest:e2e`
   - Runs `sbt kvstore/compile` to avoid long first startup

5. **Documentation**
   - Update README with new test commands
   - Document DEBUG_SERVER environment variable
   - Add troubleshooting section

**Success Criteria:**
- [ ] `npm run test:e2e` works without manual server
- [ ] All existing tests pass
- [ ] Server starts in <30s after first compile
- [ ] Cleanup is reliable (no manual pkill needed)
- [ ] Tests run in CI successfully

**Estimated Effort:** 2-3 hours

---

### Phase 3: Implement Failure Scenario Tests

**Goal:** Add tests from `MANUAL_TEST_PLAN_SERVER_CONTROL.md` that require server control

**Tasks:**

1. **Create `failure-scenarios.test.ts`**
   - Each test gets its own server instance (`beforeEach/afterEach`)
   - Implement **Critical Priority** tests:
     - ✅ Test 13: Server Not Running
     - ✅ Test 14: Server Crash Mid-Operation
     - ✅ Test 15: Watch During Server Restart
     - ✅ Test 17: Graceful Shutdown During Active Watch
   - Implement **Important Priority** tests:
     - ✅ Test 19: Multiple Clients Competing (5 concurrent)
     - ✅ Test 21: Load Test (50 rapid connections)

2. **Per-test server lifecycle**
   ```typescript
   beforeEach(async () => {
     server = new KVStoreServer({ /* config */ });
     await server.start();
   }, 120000);
   
   afterEach(async () => {
     await server.stop();
   });
   ```

3. **Test isolation strategy**
   - Use unique ports per test suite (avoid conflicts)
   - OR use sequential execution (slower but simpler)
   - OR use server reuse with cleanup between tests

4. **Performance optimization**
   - Consider reusing compiled server between tests
   - Use `sbt --client` to leverage hot SBT server
   - Explore parallel test execution where safe

**Success Criteria:**
- [ ] Test 13: Handles server not running gracefully
- [ ] Test 14: Handles server crash during operation
- [ ] Test 15: Watch survives server restart
- [ ] Test 17: Watch exits cleanly on graceful server shutdown
- [ ] Test 19: 5 concurrent clients work correctly
- [ ] Test 21: 50 rapid connections succeed (no resource leaks)
- [ ] All tests pass reliably (3+ runs)
- [ ] Total test time <10 minutes

**Estimated Effort:** 8-10 hours

---

### Phase 4: CI Integration

**Goal:** Add E2E tests to GitHub Actions workflow

**Tasks:**

1. **Create workflow file** (`.github/workflows/kvstore-e2e.yml`)
   ```yaml
   name: KVStore E2E Tests
   on:
     pull_request:
       branches: [ main ]
       paths:
         - 'kvstore/**'
         - 'kvstore-cli-ts/**'
         - '**.scala'
         - 'build.sbt'
   ```

2. **Workflow steps**
   - Setup Java 21 + SBT
   - Setup Node.js 22
   - Cache SBT dependencies
   - Pre-compile server (`sbt kvstore/compile`)
   - Install CLI dependencies
   - Build CLI
   - Run basic tests (`npm run test:e2e`)
   - Run failure tests (`npm run test:e2e:failures`)

3. **Optimization strategies**
   - Matrix strategy for parallel test execution?
   - Separate jobs for basic vs failure tests?
   - Artifact upload for server logs on failure

4. **Monitoring & debugging**
   - Add timeout for entire workflow (15 minutes)
   - Upload server logs as artifacts on failure
   - Add status badge to README

**Success Criteria:**
- [ ] E2E tests run automatically on PRs
- [ ] Tests pass consistently in CI
- [ ] Build time <10 minutes (with cache)
- [ ] Server logs available on failure
- [ ] No orphaned processes in CI

**Estimated Effort:** 2-3 hours

---

## Technical Details

### Server Startup Process

```
1. Spawn SBT process:
   sbt --client "kvstore/runMain zio.kvstore.KVStoreServerApp ..."
   
2. Wait for compilation (first run: ~60s, cached: ~5s)

3. Poll server port (tcp://127.0.0.1:7002):
   - Max attempts: 120 (60 seconds @ 500ms intervals)
   - Use net.Socket to check TCP connection
   
4. Verify connection:
   - Run test CLI command: `get __health_check__`
   - Expect success or "not found" (both OK)
   
5. Server ready!
```

### Cleanup Strategy

```typescript
// Priority order for cleanup:

1. Graceful shutdown (SIGTERM)
   - Wait up to 5 seconds
   
2. Forceful kill (SIGKILL)
   - If graceful times out
   
3. Process group kill (safety net)
   - Kill any orphaned child processes
   
4. Verify cleanup
   - Check port is free
   - No "java" processes with kvstore in command
```

### Port Management

```typescript
// Strategy to avoid port conflicts:

Option A: Sequential tests (simple)
- All tests use same port (7002)
- Run sequentially: { pool: 'forks', maxForks: 1 }

Option B: Dynamic ports (complex but parallel)
- Assign port range: 7002-7010
- Each test gets unique port
- Cleanup must be robust

Recommendation: Start with Option A, migrate to B if needed
```

### Performance Optimizations

```typescript
// 1. Use SBT client mode (reuse hot server)
spawn('sbt', ['--client', 'kvstore/runMain ...'])

// 2. Pre-compile before tests
// npm script: "pretest:e2e": "sbt kvstore/compile"

// 3. Shared server for fast tests
// Use global server in beforeAll for basic tests

// 4. Parallel execution where safe
// Basic tests (shared server): parallel ✓
// Failure tests (per-test server): sequential ✓
```

### Cross-Platform Considerations

```typescript
// macOS & Linux: SIGTERM, SIGKILL work
// Windows: Use 'taskkill /F /PID <pid>'

// Detect platform:
const isWindows = process.platform === 'win32';

if (isWindows) {
  spawn('taskkill', ['/F', '/PID', pid]);
} else {
  process.kill(pid, 'SIGKILL');
}
```

---

## File Structure

```
kvstore-cli-ts/
├── tests/
│   ├── helpers/
│   │   ├── serverManager.ts         [NEW] - Server lifecycle
│   │   ├── testHelpers.ts           [EXISTS] - CLI helpers
│   │   └── README.md                [UPDATE] - Document helpers
│   │
│   ├── e2e/
│   │   ├── cli.test.ts              [UPDATE] - Use serverManager (basic tests)
│   │   ├── failure-scenarios.test.ts [NEW] - Tests 13,14,15,17,19,21
│   │   ├── advanced-scenarios.test.ts [NEW - Phase 5] - Tests 16,18,20,22
│   │   └── README.md                [NEW] - Test documentation
│   │
│   └── unit/
│       └── ... (unchanged)
│
├── package.json                      [UPDATE] - Add scripts
├── vitest.config.ts                  [UPDATE] - Configure timeouts
├── run-e2e.sh                        [DEPRECATE] - Or keep for compat
└── E2E_TYPESCRIPT_MANAGED_PLAN.md   [THIS FILE]
```

---

## Migration Strategy

### Step 1: Add Infrastructure (Non-Breaking)
- Add `serverManager.ts` helper
- Keep existing `run-e2e.sh` working
- Add new npm scripts alongside existing ones

### Step 2: Parallel Testing
- Run both approaches to validate
- Compare reliability, speed, DX

### Step 3: Switch Default
- Update README to recommend `npm run test:e2e`
- Mark `run-e2e.sh` as deprecated

### Step 4: Remove Old Approach
- After 1-2 sprints of stability
- Delete `run-e2e.sh`
- Remove manual server instructions

---

## Risk Assessment

| Risk | Impact | Likelihood | Mitigation |
|------|--------|-----------|------------|
| Server doesn't stop cleanly | HIGH | MEDIUM | Forceful kill fallback + pgid kill |
| SBT startup too slow | MEDIUM | LOW | Pre-compile + SBT client mode |
| Port conflicts in CI | MEDIUM | LOW | Use unique ports or sequential tests |
| Cross-platform issues | LOW | MEDIUM | Test on Linux early, doc Windows limitations |
| Flaky tests | HIGH | MEDIUM | Robust wait logic, longer timeouts, retries |

---

## Success Metrics

### Developer Experience
- [ ] New contributor can run tests in one command
- [ ] No manual cleanup needed after test failures
- [ ] Debug logs easily accessible
- [ ] Test failures are reproducible

### Reliability
- [ ] Tests pass 95%+ in CI
- [ ] No orphaned processes
- [ ] Cleanup works on Ctrl+C

### Performance
- [ ] Basic tests: <2 minutes (after first compile)
- [ ] Failure tests: <5 minutes
- [ ] Total CI time: <10 minutes

### Coverage
- [ ] Phase 3: 6/10 critical & important tests automated
- [ ] Phase 5: 10/10 all manual test scenarios automated
- [ ] Load testing (50+ connections)
- [ ] Failure scenarios (crash, restart, graceful shutdown)

---

## Phase 5: Additional Test Scenarios (Future)

**Goal:** Implement remaining test scenarios from manual test plan

**Tasks:**

1. **Test 16: Session Expiry**
   - Requires server configuration changes mid-test
   - Set short session timeout (5 seconds)
   - Verify client handles expiry correctly
   - *Complexity: Medium - needs server config API*

2. **Test 18: Network Partition**
   - Simulate network partition (block TCP traffic)
   - Options:
     - iptables/pf rules (requires sudo)
     - ZMQ socket manipulation
     - Proxy/middleware approach
   - *Complexity: High - infrastructure dependent*

3. **Test 20: Registration Race Condition**
   - Watch connects during server restart
   - Very timing-sensitive
   - May require multiple attempts
   - *Complexity: High - timing-dependent*

4. **Test 22: Server Logs Validation**
   - Capture server logs during operations
   - Verify proper logging of events:
     - Client connections
     - CreateSession messages
     - ClientRequest operations
     - Disconnections
   - *Complexity: Low - log parsing*

**Success Criteria:**
- [ ] All 10 manual test scenarios automated
- [ ] Complete test coverage of failure modes
- [ ] Documentation for complex test setup

**Estimated Effort:** 1-2 weeks

---

## Phase 6: Advanced Enhancements (Optional)

1. **Cluster Testing**
   - Multi-node server management
   - Leader election tests
   - Partition tolerance tests
   - *May require Docker for complexity*

2. **Performance Benchmarking**
   - Automated performance regression tests
   - Latency percentiles (p50, p95, p99)
   - Throughput measurements

3. **Chaos Testing**
   - Random server crashes
   - Network delays/partitions
   - Resource constraints

4. **Watch Mode Optimization**
   - Keep server running between test runs
   - Hot reload on code changes

---

## Alternative: Hybrid Approach

If TypeScript-managed proves too complex or unreliable:

**Plan B: TypeScript + Docker**
- Basic tests: TypeScript-managed (fast)
- Failure tests: Docker Compose (reliable)
- Cluster tests: Docker Compose (necessary)

**Trigger:**
- Server cleanup reliability <90%
- CI flakiness >10%
- Need cluster testing before Phase 5

---

## Rollback Plan

If implementation fails or is blocked:

1. Keep existing `run-e2e.sh` approach
2. Document manual test procedures
3. Revisit Docker approach
4. Consider hiring DevOps for infrastructure

**Rollback triggers:**
- Cannot achieve reliable cleanup after 2 weeks effort
- CI flakiness unacceptable (>20% failure rate)
- Cross-platform issues unsolvable

---

## Timeline

| Phase | Duration | Dependencies | Test Coverage |
|-------|----------|--------------|---------------|
| Phase 1: Core Infrastructure | 1 week | None | 0/10 |
| Phase 2: Basic Tests | 3 days | Phase 1 | 0/10 |
| Phase 3: Failure Tests (Critical + Important) | 1.5 weeks | Phase 2 | 6/10 |
| Phase 4: CI Integration | 3 days | Phase 3 | 6/10 |
| Phase 5: Remaining Scenarios (Optional) | 1-2 weeks | Phase 4 | 10/10 |
| **Total (Phase 1-4)** | **~4 weeks** | Sequential | **6/10** |
| **Total (Phase 1-5)** | **~6 weeks** | Sequential | **10/10** |

*Note: Assumes part-time work (4-6 hours/day)*

### Phased Delivery Strategy

**MVP (Phases 1-4):** 
- Complete infrastructure
- All critical & important tests
- CI integration
- **6/10 test scenarios** (60% coverage, 100% of high-priority)

**Complete (Phase 5):**
- All nice-to-have scenarios
- **10/10 test scenarios** (100% coverage)

---

## Approval & Sign-off

- [ ] Technical approach reviewed
- [ ] Timeline acceptable
- [ ] Resource allocation confirmed
- [ ] Success criteria agreed upon

**Approved by:** _________________  
**Date:** _________________

---

## References

- **Test Specifications:** `kvstore-cli-ts/MANUAL_TEST_PLAN_SERVER_CONTROL.md` (10 test scenarios)
- Current E2E tests: `kvstore-cli-ts/tests/e2e/cli.test.ts`
- Server quickstart: `specs/004-add-client-server/quickstart.md`
- TypeScript CI: `.github/workflows/typescript-ci.yml`
- Scala CI: `.github/workflows/scala-ci.yml`

### Test Scenario Mapping

For detailed test specifications, commands, and expected behavior, refer to:
- `MANUAL_TEST_PLAN_SERVER_CONTROL.md` - Complete test definitions
  - Tests 13-17: Failure scenarios
  - Tests 18-20: Edge cases
  - Tests 19, 21: Concurrency & load
  - Test 22: Observability

---

## Notes & Decisions

**2026-01-29 - Initial Plan**
- Chose TypeScript-managed over Docker for simplicity
- Decided to support both shared and per-test server models
- Will pre-compile server to reduce startup time
- Plan to use sequential execution initially (simpler)
