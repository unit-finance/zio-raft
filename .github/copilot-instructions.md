# GitHub Copilot Custom Instructions for zio-raft Repository

## Quality Assurance Requirements

**CRITICAL**: Before completing ANY task in this repository, you MUST verify the following quality checks pass:

### For TypeScript Projects (typescript-client, kvstore-cli-ts)

When making changes to TypeScript code, **ALWAYS** run these checks before completing:

1. **Format Check and Fix**
   ```bash
   cd typescript-client && npm run format:check
   # If formatting issues found:
   npm run format
   ```
   
   ```bash
   cd kvstore-cli-ts && npm run format:check
   # If formatting issues found:
   npm run format
   ```

2. **Linting**
   ```bash
   cd typescript-client && npm run lint
   cd kvstore-cli-ts && npm run lint
   ```

3. **Type Checking**
   ```bash
   cd typescript-client && npm run typecheck
   cd kvstore-cli-ts && npm run typecheck
   ```

4. **Build**
   ```bash
   cd typescript-client && npm run build
   cd kvstore-cli-ts && npm run build
   ```

5. **Tests**
   ```bash
   cd typescript-client && npm test
   cd kvstore-cli-ts && npm test
   ```

### For Scala Projects

When making changes to Scala code, **ALWAYS** run these checks before completing:

1. **Format Check**
   ```bash
   sbt scalafmtCheck
   # If formatting issues found:
   sbt scalafmt
   ```

2. **Compilation**
   ```bash
   sbt +Test/compile
   ```

3. **Tests**
   ```bash
   sbt +test
   ```

## Pre-Commit Checklist

Before using `report_progress` to commit changes, verify:

- [ ] All formatting checks pass (or have been auto-fixed)
- [ ] All linting checks pass
- [ ] All type checks pass
- [ ] Code compiles/builds successfully
- [ ] All unit tests pass
- [ ] All integration tests pass (if applicable)
- [ ] No new errors or warnings introduced

## Standards and Best Practices

1. **No Silent Failures**: Always check command exit codes and report failures
2. **Fix Issues Immediately**: If formatting/linting issues are found, fix them before proceeding
3. **Test Coverage**: Ensure tests cover new functionality
4. **Documentation**: Update relevant documentation when changing APIs or behavior
5. **Minimal Changes**: Make the smallest possible changes to achieve the goal

## Common Commands Reference

### TypeScript Projects
- Format: `npm run format`
- Format check: `npm run format:check`
- Lint: `npm run lint`
- Lint fix: `npm run lint:fix`
- Type check: `npm run typecheck`
- Build: `npm run build`
- Test: `npm test`
- Test with coverage: `npm run test:coverage`

### Scala Projects
- Format: `sbt scalafmt`
- Format check: `sbt scalafmtCheck`
- Compile: `sbt compile`
- Test compile: `sbt Test/compile`
- Test: `sbt test`
- Cross-compile: `sbt +test`

## Workflow

1. Make code changes
2. Run formatters to auto-fix style issues
3. Run linters and fix any issues
4. Run type checks
5. Build the project
6. Run tests
7. Verify all checks pass
8. Use `report_progress` to commit
9. Reply to user with summary of checks performed

## Failure Handling

If any check fails:
1. Review the error output carefully
2. Fix the underlying issue
3. Re-run the check to verify the fix
4. Do not proceed until all checks pass
5. Report the issue and fix to the user

## Notes

- E2E tests may be skipped if they require external services (this is acceptable)
- Pre-existing warnings in dependencies are acceptable (document them)
- Always prefer fixing issues over disabling checks
- When in doubt, ask the user before disabling any quality check
