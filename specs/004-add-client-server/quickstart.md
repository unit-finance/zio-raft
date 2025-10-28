# Quickstart: kvstore client-server example with watch

## Prerequisites
- Scala 3.3+, sbt

## Run the server
```bash
sbt "kvstore/runMain zio.kvstore.KVStoreServerApp --serverPort 7001 --memberId node-1 --members node-1=tcp://127.0.0.1:7002"
```

## Run the CLI
```bash
sbt "kvstore-cli/run"
```

## Basic usage
- Set a key:
```bash
kvstore-cli set k1 v1
```
- Get a key:
```bash
kvstore-cli get k1
```
- Watch a key (returns current value, then streams updates until session expires):
```bash
kvstore-cli watch k1
```
- Update the key from another terminal:
```bash
kvstore-cli set k1 v2
```
- Observe the watch output showing v1 (initial) then v2...

## Notes
- Unlimited concurrent watches; duplicate watch requests are idempotent
- All updates are delivered; ordering is not guaranteed across sessions
- Deletes are not supported in this example
- If the session expires (expire-session), the watch stops and subscriptions are removed
