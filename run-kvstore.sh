#!/usr/bin/env bash

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

ASSEMBLY_JAR="kvstore/target/scala-3.3.7/kvstore.jar"
FORCE_REBUILD=false

# Parse flags
APP_ARGS=()
for arg in "$@"; do
    case $arg in
        --rebuild|-r)
            FORCE_REBUILD=true
            ;;
        *)
            APP_ARGS+=("$arg")
            ;;
    esac
done

echo -e "${GREEN}KVStore Server Runner${NC}"
echo "====================="
echo

# Check if we should rebuild
if [ "$FORCE_REBUILD" = true ]; then
    echo -e "${YELLOW}Force rebuild requested. Building assembly JAR...${NC}"
    sbt "kvstore/assembly"
    echo
elif [ ! -f "$ASSEMBLY_JAR" ]; then
    echo -e "${YELLOW}Assembly JAR not found. Building...${NC}"
    sbt "kvstore/assembly"
    echo
fi

# Verify the jar file exists
if [ ! -f "$ASSEMBLY_JAR" ]; then
    echo -e "${RED}Error: Failed to find assembly JAR at $ASSEMBLY_JAR${NC}"
    exit 1
fi

echo -e "${GREEN}Starting KVStore server...${NC}"
echo "JAR: $ASSEMBLY_JAR"
echo "Args: ${APP_ARGS[@]}"
echo
echo -e "${YELLOW}Press Ctrl+C to stop the server${NC}"
echo

# Run the jar with any provided arguments
exec java -jar "$ASSEMBLY_JAR" "${APP_ARGS[@]}"
