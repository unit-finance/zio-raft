# zio-raft
Raft algorithm implemented in scala zio

## Example: KVStore with Client/Server and CLI

See `specs/004-add-client-server/quickstart.md` for a quickstart that demonstrates:

- Running the KVStore server app (`kvstore` module)
- Using the CLI (`kvstore-cli` module) to `set`, `get`, and `watch` keys

Modules involved:

- `kvstore`: server app and Raft integration
- `kvstore-cli`: CLI using zio-cli and client-server-client
- `kvstore-protocol`: shared client request/response types and codecs
