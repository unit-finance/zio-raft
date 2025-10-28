# Quickstart: Query support + kvstore GET via Query

## Run cluster and server
1. Start your Raft cluster as usual.
2. Run the client-server leader node.

## Use kvstore-cli with Query-based GET
- GET now issues a Query (read-only, leader-served, linearizable).
- A client-generated correlationId (opaque string) is attached automatically.

### Examples
```bash
# Set value using existing write path
kvstore-cli set foo bar

# Read value using Query-based GET
kvstore-cli get foo
```

## Behavior
- Retries/backoff/timeouts match ClientRequest defaults.
- No auth is required (same as current behavior).
- If leadership changes, the client retries to the new leader.
