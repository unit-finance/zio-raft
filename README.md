# zio-raft
Raft algorithm implemented in scala zio

## Quick Start

Run the KVStore server:

```bash
./run-kvstore.sh
```

The script builds and runs the server with proper signal handling. Press Ctrl+C for clean shutdown.

Force rebuild the JAR after code changes:

```bash
./run-kvstore.sh --rebuild
# or use the short flag
./run-kvstore.sh -r
```

Pass configuration options:

```bash
./run-kvstore.sh --serverPort 7001 --memberId node-1
# Combine with rebuild
./run-kvstore.sh --rebuild --serverPort 7001
```

## Example: KVStore with Client/Server and CLI

See `specs/004-add-client-server/quickstart.md` for a quickstart that demonstrates:

- Running the KVStore server app (`kvstore` module)
- Using the CLI (`kvstore-cli` module) to `set`, `get`, and `watch` keys

Modules involved:

- `kvstore`: server app and Raft integration
- `kvstore-cli`: CLI using zio-cli and client-server-client
- `kvstore-protocol`: shared client request/response types and codecs
