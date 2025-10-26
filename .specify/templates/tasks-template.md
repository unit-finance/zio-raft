# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`
**Prerequisites**: plan.md (required), research.md, data-model.md, contracts/

## Execution Flow (main)
```
1. Load plan.md from feature directory
   → If not found: ERROR "No implementation plan found"
   → Extract: tech stack, libraries, structure
2. Load optional design documents:
   → data-model.md: Extract entities → model tasks
   → research.md: Extract decisions → setup tasks
3. Generate tasks by category:
   → Setup: project init, dependencies, linting
   → Tests: contract tests, integration tests
   → Core: models, services, CLI commands
   → Integration: DB, middleware, logging
   → Polish: unit tests, performance, docs
4. Apply task rules:
   → Different files = mark [P] for parallel
   → Same file = sequential (no [P])
   → Tests before implementation (TDD)
5. Number tasks sequentially (T001, T002...)
6. Generate dependency graph
7. Create parallel execution examples
8. Validate task completeness:
   → All contracts have tests?
   → All entities have models?
   → All endpoints implemented?
9. Return: SUCCESS (tasks ready for execution)
```

## Format: `[ID] [P?] Description`
- **[P]**: Can run in parallel (different files, no dependencies)
- Include exact file paths in descriptions

## Path Conventions
- **Single project**: `src/`, `tests/` at repository root
- **Web app**: `backend/src/`, `frontend/src/`
- **Mobile**: `api/src/`, `ios/src/` or `android/src/`
- Paths shown below assume single project - adjust based on plan.md structure

## Phase 3.1: Setup
- [ ] T001 Create project structure per implementation plan
- [ ] T002 Initialize Scala 3.3+ project with ZIO 2.1+ dependencies
- [ ] T003 [P] Configure scalafmt, scalafix, and strict compilation flags (-Wunused:imports)

## Phase 3.2: Tests First (TDD) ⚠️ MUST COMPLETE BEFORE 3.3
**CRITICAL: These tests MUST be written and MUST FAIL before ANY implementation**
- [ ] T004 [P] Property-based tests for complex logic using ZIO Test Gen
- [ ] T005 [P] Integration tests for external interactions (RPC, storage)
- [ ] T006 [P] Performance benchmark tests for critical paths

## Phase 3.3: Core Implementation (ONLY after tests are failing)
- [ ] T007 [P] Immutable data models with proper type safety
- [ ] T008 [P] Service layer using ZIO effects and explicit error types
- [ ] T009 [P] Extend existing abstractions (StateMachine, RPC, LogStore) without breaking changes
- [ ] T010 Implement functionality using ZIO ecosystem (ZStream, ZLayer, etc.)
- [ ] T011 Add explicit error handling for all external interactions
- [ ] T012 Input validation using ZIO Prelude or similar type-safe validation
- [ ] T013 Resource management using ZIO Scope patterns

## Phase 3.4: Integration
- [ ] T014 Integrate with existing storage abstractions using ZIO patterns
- [ ] T015 Add distributed system error handling (timeouts, retries)
- [ ] T016 Implement observability using ZIO logging and metrics
- [ ] T017 Ensure backward compatibility and performance preservation

## Phase 3.5: Polish
- [ ] T018 [P] Unit tests covering edge cases and error scenarios
- [ ] T019 Performance validation and benchmark comparison with baseline
- [ ] T020 [P] Update documentation and API specifications
- [ ] T021 Code review for ZIO ecosystem consistency and functional purity
- [ ] T022 Constitution compliance verification checklist

## Dependencies
- Tests (T004-T006) before implementation (T008-T014)
- T007 blocks T008, T014
- T015 blocks T017
- Implementation before polish (T018-T022)

## Parallel Example
```
# Launch T004-T007 together:
Task: "Contract test POST /api/users in tests/contract/test_users_post.py"
Task: "Contract test GET /api/users/{id} in tests/contract/test_users_get.py"
Task: "Integration test registration in tests/integration/test_registration.py"
Task: "Integration test auth in tests/integration/test_auth.py"
```

## Notes
- [P] tasks = different files, no dependencies
- Verify tests fail before implementing
- Commit after each task
- Avoid: vague tasks, same file conflicts

## Task Generation Rules
*Applied during main() execution*

1. **From Contracts**:
   - Each contract file → contract test task [P]
   - Each endpoint → implementation task
   
2. **From Data Model**:
   - Each entity → model creation task [P]
   - Relationships → service layer tasks
   
3. **From User Stories**:
   - Each story → integration test [P]
   - Quickstart scenarios → validation tasks

4. **Ordering**:
   - Setup → Tests → Models → Services → Endpoints → Polish
   - Dependencies block parallel execution

## Validation Checklist
*GATE: Checked by main() before returning*

- [ ] All functionality has corresponding ZIO Test specifications
- [ ] All data models use immutable structures and type safety
- [ ] All tests come before implementation (TDD compliance)
- [ ] Parallel tasks truly independent and don't affect shared state
- [ ] Each task preserves existing abstractions and APIs
- [ ] No task introduces unsafe operations or exceptions for business logic
- [ ] ZIO ecosystem consistency maintained throughout