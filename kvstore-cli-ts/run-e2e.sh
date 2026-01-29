#!/bin/bash
#
# Run KVStore CLI E2E Tests
#
# Prerequisites:
#   - KVStore server running on tcp://127.0.0.1:7002
#   - CLI built: npm run build
#
# Usage:
#   ./run-e2e.sh          # Run all E2E tests
#   ./run-e2e.sh --watch  # Run in watch mode
#

set -e

cd "$(dirname "$0")"

# Check if CLI is built
if [ ! -f "dist/index.js" ]; then
    echo "CLI not built. Building..."
    npm run build
fi

# Run E2E tests
if [ "$1" = "--watch" ]; then
    npm test -- --watch tests/e2e/cli.test.ts
else
    npm test -- --run tests/e2e/cli.test.ts
fi
