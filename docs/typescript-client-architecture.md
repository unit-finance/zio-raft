# TypeScript Client Architecture

## High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              kvstore-cli-ts                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                           CLI Layer                                         ││
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐                            ││
│  │  │  index.ts  │──│  commands/ │──│ validation │                            ││
│  │  │  (main)    │  │ set/get/   │  │     .ts    │                            ││
│  │  └────────────┘  │ watch      │  └────────────┘                            ││
│  │                  └─────┬──────┘                                            ││
│  │                        │                                                    ││
│  │  ┌─────────────────────┴─────────────────────────────────────────────────┐ ││
│  │  │                       kvClient.ts                                      │ ││
│  │  │                  (Protocol Wrapper)                                    │ ││
│  │  │  • Typed API for KVStore operations                                   │ ││
│  │  │  • Encodes/decodes domain messages                                    │ ││
│  │  └───────────────────────────┬───────────────────────────────────────────┘ ││
│  │                              │                                              ││
│  │  ┌───────────────────────────┴────────────────────────────────────────────┐ ││
│  │  │                        codecs.ts                                        │ ││
│  │  │  • KVStore-specific binary encoding                                    │ ││
│  │  │  • Set/Get/Watch request encoding                                      │ ││
│  │  │  • Result/Notification decoding                                        │ ││
│  │  └────────────────────────────────────────────────────────────────────────┘ ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
│                                        │                                         │
│                                        ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                        @zio-raft/typescript-client                          ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────┐
│                          @zio-raft/typescript-client                             │
│                                                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                            Public API Layer                                │  │
│  │  ┌────────────────────────────────────────────────────────────────────┐   │  │
│  │  │                          RaftClient                                 │   │  │
│  │  │  • EventEmitter-based async interface                              │   │  │
│  │  │  • Promise-based API: connect(), disconnect(),                     │   │  │
│  │  │    submitCommand(), submitQuery()                                  │   │  │
│  │  │  • Session lifecycle management                                    │   │  │
│  │  │  • Automatic reconnection                                          │   │  │
│  │  └────────────────────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                                        │                                         │
│                    ┌───────────────────┴───────────────────┐                    │
│                    │                                       │                    │
│                    ▼                                       ▼                    │
│  ┌────────────────────────────────────┐  ┌───────────────────────────────────┐ │
│  │          State Machine             │  │        Stream Merger              │ │
│  │  ┌──────────────────────────────┐  │  │  (Event Loop)                     │ │
│  │  │       StateManager           │  │  │  ┌─────────────────────────────┐  │ │
│  │  │  • Orchestrates handlers     │  │  │  │ mergeStreams()              │  │ │
│  │  │  • Routes events to states   │  │  │  │  • Action stream            │  │ │
│  │  └──────────────────────────────┘  │  │  │  • Server message stream    │  │ │
│  │                │                   │  │  │  • KeepAlive stream         │  │ │
│  │  ┌─────────────┴─────────────────┐ │  │  │  • Timeout stream           │  │ │
│  │  │      State Handlers           │ │  │  └─────────────────────────────┘  │ │
│  │  │  ┌──────────────────────────┐ │ │  └───────────────────────────────────┘ │
│  │  │  │ DisconnectedHandler     │ │ │                                         │
│  │  │  ├──────────────────────────┤ │ │                                         │
│  │  │  │ ConnectingNewSession    │ │ │                                         │
│  │  │  │         Handler         │ │ │                                         │
│  │  │  ├──────────────────────────┤ │ │                                         │
│  │  │  │ ConnectingExisting      │ │ │                                         │
│  │  │  │    SessionHandler       │ │ │                                         │
│  │  │  ├──────────────────────────┤ │ │                                         │
│  │  │  │ ConnectedHandler        │ │ │                                         │
│  │  │  └──────────────────────────┘ │ │                                         │
│  │  └───────────────────────────────┘ │                                         │
│  └────────────────────────────────────┘                                         │
│                    │                                                             │
│                    ▼                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                         Request Tracking Layer                             │  │
│  │  ┌────────────────────┐ ┌─────────────────┐ ┌──────────────────────────┐  │  │
│  │  │  PendingRequests   │ │ PendingQueries  │ │ ServerRequestTracker     │  │  │
│  │  │  (commands)        │ │ (queries)       │ │ (deduplication)          │  │  │
│  │  └────────────────────┘ └─────────────────┘ └──────────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                    │                                                             │
│                    ▼                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                          Protocol Layer                                    │  │
│  │  ┌────────────────────────────┐  ┌──────────────────────────────────────┐ │  │
│  │  │       messages.ts          │  │           codecs.ts                  │ │  │
│  │  │  • ClientMessage types     │  │  • Binary encode/decode              │ │  │
│  │  │  • ServerMessage types     │  │  • Matches Scala scodec              │ │  │
│  │  │  • Discriminated unions    │  │  • Protocol header handling          │ │  │
│  │  └────────────────────────────┘  └──────────────────────────────────────┘ │  │
│  │                                                                            │  │
│  │  ┌────────────────────────────────────────────────────────────────────┐   │  │
│  │  │                       constants.ts                                  │   │  │
│  │  │  • PROTOCOL_SIGNATURE = "zraft"                                    │   │  │
│  │  │  • Message type discriminators                                     │   │  │
│  │  │  • Reason codes                                                    │   │  │
│  │  └────────────────────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                    │                                                             │
│                    ▼                                                             │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                          Transport Layer                                   │  │
│  │  ┌────────────────────────────────────────────────────────────────────┐   │  │
│  │  │              ClientTransport (interface)                            │   │  │
│  │  │  • connect(address): Promise<void>                                 │   │  │
│  │  │  • disconnect(): Promise<void>                                     │   │  │
│  │  │  • sendMessage(message): Promise<void>                             │   │  │
│  │  │  • incomingMessages: AsyncIterable<ServerMessage>                  │   │  │
│  │  └────────────────────────────────────────────────────────────────────┘   │  │
│  │                              ▲                                            │  │
│  │              ┌───────────────┴───────────────┐                           │  │
│  │              │                               │                           │  │
│  │  ┌──────────────────────┐      ┌──────────────────────────────────────┐ │  │
│  │  │    ZmqTransport      │      │         MockTransport               │ │  │
│  │  │  (production)        │      │         (testing)                   │ │  │
│  │  │  • CLIENT socket     │      │  • Message injection                │ │  │
│  │  │  • Heartbeat config  │      │  • Sent message tracking            │ │  │
│  │  └──────────────────────┘      └──────────────────────────────────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
│                                                                                  │
│  ┌───────────────────────────────────────────────────────────────────────────┐  │
│  │                          Support Modules                                   │  │
│  │  ┌─────────────┐  ┌────────────┐  ┌─────────┐  ┌──────────┐  ┌─────────┐ │  │
│  │  │  types.ts   │  │ config.ts  │  │errors.ts│  │events/   │  │ utils/  │ │  │
│  │  │  Branded    │  │ ClientConf │  │ Error   │  │ Names    │  │AsyncQueue│ │  │
│  │  │  types      │  │ Validation │  │ Classes │  │ Types    │  │StreamMerge│ │  │
│  │  └─────────────┘  └────────────┘  └─────────┘  └──────────┘  └─────────┘ │  │
│  └───────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## Component Summary

### kvstore-cli-ts (Application Layer)

| Component | Purpose | Dependencies |
|-----------|---------|--------------|
| `index.ts` | CLI entry point, command registration | Commander.js |
| `commands/` | Individual command implementations | kvClient, validation, formatting |
| `kvClient.ts` | Typed wrapper for KVStore operations | typescript-client, codecs |
| `codecs.ts` | KVStore-specific binary encoding | errors, types |
| `validation.ts` | Input validation for CLI args | errors, types |
| `formatting.ts` | Output formatting utilities | errors, types |
| `errors.ts` | Error class hierarchy | - |
| `types.ts` | Domain type definitions | - |

### typescript-client (Library Layer)

| Component | Purpose | Dependencies |
|-----------|---------|--------------|
| `client.ts` | Main public API (RaftClient) | All internal modules |
| `config.ts` | Configuration with defaults and validation | types |
| `state/clientState.ts` | State machine with handlers | protocol, pending*, types |
| `state/pendingRequests.ts` | In-flight command tracking | types |
| `state/pendingQueries.ts` | In-flight query tracking | types |
| `state/serverRequestTracker.ts` | Deduplication for server requests | types |
| `protocol/messages.ts` | Message type definitions | types |
| `protocol/codecs.ts` | Binary encoding/decoding | types, constants |
| `protocol/constants.ts` | Protocol constants and enums | - |
| `transport/transport.ts` | Transport interface | protocol/messages |
| `transport/zmqTransport.ts` | ZeroMQ implementation | transport, protocol/codecs |
| `testing/MockTransport.ts` | Test implementation | transport, utils/asyncQueue |
| `events/eventNames.ts` | Event name constants | - |
| `events/eventTypes.ts` | Event type definitions | types, protocol, state |
| `utils/asyncQueue.ts` | Async-iterable queue | - |
| `utils/streamMerger.ts` | Merge async iterables | - |
| `types.ts` | Branded type definitions | - |
| `errors.ts` | Error class hierarchy | types |

## Data Flow

```
User CLI Command
       │
       ▼
┌─────────────────┐
│ kvstore-cli-ts  │ Parse args, validate, create KVClient
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   KVClient      │ Encode domain request → Buffer
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   RaftClient    │ Queue action, state machine processes
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ State Machine   │ Generate ClientMessage, track pending
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Protocol Codec  │ Encode to binary with header
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ ZmqTransport    │ Send over ZeroMQ CLIENT socket
└────────┬────────┘
         │
         ▼
     [ Network ]
         │
         ▼
    ZIO Raft Server
```

## State Machine States

```
┌──────────────┐     Connect      ┌───────────────────────┐
│ Disconnected │─────────────────▶│ ConnectingNewSession  │
└──────────────┘                  └───────────┬───────────┘
       ▲                                      │
       │                          SessionCreated
       │ SessionExpired                       │
       │ Shutdown                             ▼
       │                          ┌───────────────────────┐
       └──────────────────────────│      Connected        │
                                  └───────────┬───────────┘
                                              │
                                  NotLeaderAnymore
                                              │
                                              ▼
                                  ┌───────────────────────┐
                                  │ConnectingExisting     │
                                  │       Session         │
                                  └───────────────────────┘
```
