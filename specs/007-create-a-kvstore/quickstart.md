# Quick Start: TypeScript KVStore CLI Client

**Feature**: 007-create-a-kvstore  
**Date**: 2026-01-13  
**Status**: Complete

## Overview

This guide will get you up and running with the TypeScript KVStore CLI client in under 5 minutes.

---

## Prerequisites

- **Node.js**: 18.0.0 or higher
- **npm**: 8.0.0 or higher
- **Running KVStore Cluster**: At least one KVStore node accessible via TCP

Check your versions:
```bash
node --version  # Should be v18.0.0 or higher
npm --version   # Should be 8.0.0 or higher
```

---

## Installation

### Option 1: Install from Source (Development)

```bash
# Clone the repository (if not already cloned)
cd /path/to/zio-raft

# Navigate to the CLI project
cd kvstore-cli-ts

# Install dependencies
npm install

# Build the CLI
npm run build

# Link globally for testing
npm link

# Verify installation
kvstore --version
kvstore --help
```

### Option 2: Install from npm (Once Published)

```bash
# Install globally
npm install -g @zio-raft/kvstore-cli

# Verify installation
kvstore --version
```

---

## Quick Start - 3 Commands in 60 Seconds

### 1. Set a Value

Store a key-value pair in the cluster:

```bash
kvstore set mykey "Hello, KVStore!"
```

**Expected output:**
```
OK
```

**With custom cluster endpoints:**
```bash
kvstore set mykey "Hello" --endpoints "node-1=tcp://192.168.1.10:7001,node-2=tcp://192.168.1.11:7001"
```

---

### 2. Get a Value

Retrieve the value for a key:

```bash
kvstore get mykey
```

**Expected output:**
```
mykey = Hello, KVStore!
```

**For non-existent key:**
```bash
kvstore get nonexistent
```

**Expected output:**
```
nonexistent = <none>
```

---

### 3. Watch for Updates

Monitor a key for real-time updates:

```bash
kvstore watch mykey
```

**Expected output:**
```
watching mykey - press Ctrl+C to stop
```

**In another terminal, update the value:**
```bash
kvstore set mykey "Updated value"
```

**Watch terminal shows:**
```
[2026-01-13T10:15:23.456Z] seq=42 key=mykey value=Updated value
```

**Stop watching:**
Press `Ctrl+C` to gracefully stop the watch command.

---

## Common Workflows

### Scenario 1: Development with Local Cluster

```bash
# Start local KVStore cluster (in separate terminal)
# Assuming cluster running on default: tcp://127.0.0.1:7001

# Set configuration values
kvstore set config.db.host "localhost"
kvstore set config.db.port "5432"
kvstore set config.feature.enabled "true"

# Verify configuration
kvstore get config.db.host
kvstore get config.db.port
kvstore get config.feature.enabled

# Watch for configuration changes
kvstore watch config.feature.enabled
```

---

### Scenario 2: Production Cluster with Multiple Nodes

```bash
# Define cluster endpoints
ENDPOINTS="node-1=tcp://prod1.example.com:7001,node-2=tcp://prod2.example.com:7001,node-3=tcp://prod3.example.com:7001"

# Store critical data
kvstore set "user:1001:email" "alice@example.com" -e "$ENDPOINTS"

# Retrieve data
kvstore get "user:1001:email" -e "$ENDPOINTS"

# Monitor real-time updates
kvstore watch "metrics:requests:per_second" -e "$ENDPOINTS"
```

---

### Scenario 3: Configuration Management

```bash
# Set environment-specific config
kvstore set "env:prod:db:connectionString" "postgresql://..."
kvstore set "env:prod:cache:ttl" "3600"

# Deploy new service - watch for config updates
kvstore watch "env:prod:feature:newFeature"

# When DevOps enables feature:
# kvstore set "env:prod:feature:newFeature" "enabled"
# Your watch command immediately sees the change
```

---

## Command Reference

### Set Command

**Syntax:**
```bash
kvstore set <key> <value> [options]
```

**Arguments:**
- `<key>`: Key to set (max 256 bytes UTF-8)
- `<value>`: Value to store (max 1MB UTF-8)

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: "node-1=tcp://127.0.0.1:7001")

**Examples:**
```bash
# Simple set
kvstore set greeting "Hello, World!"

# With spaces in value
kvstore set message "This is a multi-word value"

# With unicode
kvstore set 欢迎 "Welcome"

# With custom endpoints
kvstore set key value -e "node-1=tcp://10.0.1.5:7001"
```

---

### Get Command

**Syntax:**
```bash
kvstore get <key> [options]
```

**Arguments:**
- `<key>`: Key to retrieve (max 256 bytes UTF-8)

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: "node-1=tcp://127.0.0.1:7001")

**Examples:**
```bash
# Simple get
kvstore get greeting

# Get non-existent key (not an error)
kvstore get nonexistent  # Output: nonexistent = <none>

# With custom endpoints
kvstore get key -e "node-1=tcp://10.0.1.5:7001"
```

---

### Watch Command

**Syntax:**
```bash
kvstore watch <key> [options]
```

**Arguments:**
- `<key>`: Key to watch (max 256 bytes UTF-8)

**Options:**
- `-e, --endpoints <endpoints>`: Cluster endpoints (default: "node-1=tcp://127.0.0.1:7001")

**Examples:**
```bash
# Watch a key
kvstore watch config.feature

# Output shows all updates with timestamps and sequence numbers:
# [2026-01-13T10:15:23.456Z] seq=42 key=config.feature value=enabled
# [2026-01-13T10:16:45.789Z] seq=43 key=config.feature value=disabled

# Stop with Ctrl+C
# Output: Shutting down...
```

**Note**: Watch runs indefinitely until interrupted. It automatically reconnects if connection is lost.

---

## Global Options

```bash
# Show version
kvstore --version

# Show help for all commands
kvstore --help

# Show help for specific command
kvstore set --help
kvstore get --help
kvstore watch --help
```

---

## Configuration

### Default Endpoints

If you frequently connect to the same cluster, set an environment variable:

```bash
# In your ~/.bashrc or ~/.zshrc
export KVSTORE_ENDPOINTS="node-1=tcp://dev.example.com:7001,node-2=tcp://dev.example.com:7002"

# Or create an alias
alias kvstore-dev="kvstore --endpoints 'node-1=tcp://dev.example.com:7001'"
alias kvstore-prod="kvstore --endpoints 'node-1=tcp://prod.example.com:7001'"

# Usage
kvstore-dev set mykey myvalue
kvstore-prod get mykey
```

---

## Troubleshooting

### Error: Could not connect to cluster (timeout after 5s)

**Cause**: Cluster is unreachable or not running.

**Solutions**:
1. Verify cluster is running:
   ```bash
   # Check if port is open
   nc -zv 127.0.0.1 7001
   ```

2. Check endpoints are correct:
   ```bash
   # Verify endpoint format
   kvstore get test -e "node-1=tcp://127.0.0.1:7001"
   ```

3. Check network connectivity:
   ```bash
   ping 127.0.0.1
   ```

---

### Error: Key exceeds 256 bytes (actual: 300 bytes)

**Cause**: Key is too long.

**Solutions**:
1. Shorten the key name
2. Use abbreviations or shorter identifiers
3. Remember: UTF-8 multi-byte characters count as multiple bytes
   ```bash
   # "键" is 3 bytes in UTF-8, not 1
   ```

---

### Error: Value exceeds 1MB (actual: 1500000 bytes)

**Cause**: Value is too large.

**Solutions**:
1. Store large data elsewhere (database, object storage)
2. Store reference/ID in KVStore instead of full data
3. Compress data before storing (if appropriate)

---

### Error: Invalid endpoint format

**Cause**: Endpoint string is malformed.

**Solutions**:
1. Verify format: `memberId=tcp://host:port`
2. Multiple endpoints: comma-separated, no spaces
   ```bash
   # Correct
   -e "node-1=tcp://host1:7001,node-2=tcp://host2:7001"
   
   # Incorrect (spaces)
   -e "node-1=tcp://host1:7001, node-2=tcp://host2:7001"
   ```

---

### Watch Command Not Showing Updates

**Causes and Solutions**:

1. **Wrong key**: Ensure key matches exactly
   ```bash
   # Case-sensitive!
   kvstore watch MyKey  # Will NOT see updates to "mykey"
   ```

2. **No updates occurring**: Verify by running set in another terminal
   ```bash
   # Terminal 1
   kvstore watch mykey
   
   # Terminal 2
   kvstore set mykey "new value"
   ```

3. **Connection lost**: Watch will automatically reconnect and resume

---

### Ctrl+C Not Working

**Cause**: Signal not being caught properly.

**Solutions**:
1. Try `Ctrl+\` (SIGQUIT) as fallback
2. If completely stuck: `kill <pid>` from another terminal
3. Report as bug if reproducible

---

## Validation Checks

Before running commands, CLI validates:

✅ **Keys**:
- Non-empty
- Valid UTF-8
- ≤256 bytes

✅ **Values**:
- Non-empty
- Valid UTF-8
- ≤1MB (1,048,576 bytes)

✅ **Endpoints**:
- Format: `tcp://host:port`
- Valid port (1-65535)
- At least one endpoint

---

## Exit Codes

Understanding exit codes for scripting:

- `0`: Success
- `1`: Validation error (bad input)
- `2`: Connection/timeout error
- `3`: Protocol/server error
- `130`: Interrupted by SIGINT (Ctrl+C)

**Example usage in scripts:**
```bash
#!/bin/bash

kvstore set deployment.status "in-progress"
if [ $? -eq 0 ]; then
  echo "Status updated successfully"
else
  echo "Failed to update status"
  exit 1
fi
```

---

## Performance Notes

- **Set operations**: ~1-10ms latency (cluster-dependent)
- **Get operations**: ~1-5ms latency (read-only, faster than set)
- **Watch operations**: Real-time, ~sub-millisecond notification delivery
- **Timeout**: All operations timeout after 5 seconds (fast-fail)
- **Throughput**: CLI can handle 1K-10K operations/second (client library capacity)

---

## Next Steps

After completing this quickstart:

1. **Explore the TypeScript Client Library**: Learn about the underlying `@zio-raft/typescript-client` for building custom applications

2. **Review the Scala KVStore CLI**: Compare behavior with the reference implementation:
   ```bash
   cd kvstore-cli
   sbt run
   ```

3. **Build Custom Tools**: Use the CLI as a reference for building domain-specific tools on top of the TypeScript client

4. **Contribute**: Found a bug or have a feature idea? Open an issue or PR!

---

## Comparison with Scala kvstore-cli

The TypeScript implementation matches the Scala version:

| Feature | Scala kvstore-cli | TypeScript kvstore-cli |
|---------|-------------------|------------------------|
| Set command | ✅ | ✅ |
| Get command | ✅ | ✅ |
| Watch command | ✅ | ✅ |
| Custom endpoints | ✅ (--endpoints) | ✅ (--endpoints) |
| Default endpoint | ✅ | ✅ |
| Binary protocol | ✅ (scodec) | ✅ (compatible) |
| Reconnection | ✅ (automatic) | ✅ (automatic) |
| Signal handling | ✅ | ✅ |
| Output format | Similar | Verbose (with timestamps/seq) |

---

## Examples Repository

Additional examples and use cases:

```
kvstore-cli-ts/examples/
├── basic-usage.sh              # Simple set/get/watch
├── multi-node-cluster.sh       # Production cluster setup
├── watch-configuration.sh      # Config change monitoring
└── batch-operations.sh         # Scripted bulk operations
```

---

## Support

- **Documentation**: See `/docs` in the repository
- **Issues**: https://github.com/zio/zio-raft/issues
- **Slack**: #zio-raft on Scala Slack

---

**Status**: ✅ Quickstart complete, ready for users
