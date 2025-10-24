# Quickstart: Client-Server Communication for Raft Protocol

**Feature**: 001-implement-client-server  
**Date**: 2025-09-24  
**Status**: Ready for Implementation

## Overview
This quickstart guide demonstrates the essential integration tests for client-server communication with ZIO Raft, covering session management, command submission, and server-initiated requests.

## Prerequisites
- ZIO Raft cluster running with 3+ nodes
- ZeroMQ library available
- Client and server libraries compiled and available

## Test Scenario 1: Basic Session Management

### Setup
```scala
// Start a 3-node Raft cluster
val cluster = TestRaftCluster.start(nodeCount = 3)
val leader = cluster.waitForLeader()

// Create client configuration
val clientConfig = ClientConfig(
  clusterAddresses = cluster.addresses,
  capabilities = Map("test-worker" -> "v1.0"),
  heartbeatInterval = 10.seconds,
  sessionTimeout = 30.seconds
)
```

### Test: Create and Maintain Session
```scala
test("Create session and maintain with heartbeats") {
  for {
    // Create client and establish session
    client <- RaftClient.connect(clientConfig)
    sessionId <- client.createSession()
    
    // Verify session is active
    isActive <- client.isSessionActive(sessionId)
    _ <- assertTrue(isActive)
    
    // Wait for several heartbeat cycles
    _ <- ZIO.sleep(35.seconds)
    
    // Verify session still active
    stillActive <- client.isSessionActive(sessionId)
    _ <- assertTrue(stillActive)
    
    // Clean up
    _ <- client.closeSession()
  } yield ()
}
```

### Expected Results
- [x] Session created successfully with server-generated UUID
- [x] Client receives SessionCreated confirmation
- [x] Heartbeat messages exchanged every 10 seconds
- [x] Session remains active after 35 seconds
- [x] Session closed gracefully

## Test Scenario 2: Command Submission and Leadership

### Test: Submit Command to Leader
```scala
test("Submit command to leader and receive response") {
  for {
    client <- RaftClient.connect(clientConfig)
    sessionId <- client.createSession()
    
    // Submit a test command
    command = TestCommand("increment", Map("value" -> 42))
    response <- client.submitCommand(sessionId, command, timeout = 30.seconds)
    
    // Verify successful execution
    _ <- assertTrue(response.isSuccess)
    _ <- assertTrue(response.commitIndex > 0)
    
    _ <- client.closeSession()
  } yield ()
}
```

### Test: Command Redirection from Follower
```scala
test("Handle leader redirection correctly") {
  for {
    client <- RaftClient.connect(clientConfig.copy(
      // Connect to follower node instead of leader
      clusterAddresses = List(cluster.followerAddress)
    ))
    sessionId <- client.createSession()
    
    // Submit command to follower
    command = TestCommand("increment", Map("value" -> 99))
    response <- client.submitCommand(sessionId, command, timeout = 30.seconds)
    
    // Verify command succeeded despite redirection
    _ <- assertTrue(response.isSuccess)
    _ <- assertTrue(response.commitIndex > 0)
    
    _ <- client.closeSession()
  } yield ()
}
```

### Expected Results
- [x] Command submitted successfully to leader
- [x] Command response received with commit index
- [x] Follower redirects client to leader
- [x] Client automatically retries to leader
- [x] Command execution succeeds after redirection

## Test Scenario 3: Session Durability During Leader Change

### Test: Session Survives Leader Election
```scala
test("Session persists through leader change") {
  for {
    client <- RaftClient.connect(clientConfig)
    sessionId <- client.createSession()
    
    // Verify initial routing ID mapping established
    initialRoutingId <- cluster.getRoutingIdForSession(sessionId)
    _ <- assertTrue(initialRoutingId.isDefined)
    
    // Submit initial command
    command1 = TestCommand("set", Map("key" -> "test", "value" -> "initial"))
    response1 <- client.submitCommand(sessionId, command1, timeout = 30.seconds)
    
    // Force leader election by stopping current leader
    currentLeader <- cluster.getCurrentLeader()
    _ <- cluster.stopNode(currentLeader)
    _ <- cluster.waitForNewLeader()
    
    // Client reconnects to new leader - routing ID will change
    // but session ID remains the same
    command2 = TestCommand("get", Map("key" -> "test"))
    response2 <- client.submitCommand(sessionId, command2, timeout = 30.seconds)
    
    // Verify session works with new leader and new routing ID
    newRoutingId <- cluster.getRoutingIdForSession(sessionId)
    _ <- assertTrue(newRoutingId.isDefined)
    _ <- assertTrue(newRoutingId != initialRoutingId) // Different routing ID
    _ <- assertTrue(response2.isSuccess)
    _ <- assertTrue(response2.data.contains("initial"))
    
    _ <- client.closeSession()
  } yield ()
}
```

### Expected Results
- [x] Session created successfully
- [x] Initial command executed and committed
- [x] Leader election triggered by node failure
- [x] New leader elected within election timeout
- [x] Session state preserved in new leader (from Raft)
- [x] Keep-alive tracking initialized on new leader
- [x] Subsequent commands work with new leader

## Test Scenario 4: Server-Initiated Request Processing

### Test: Server Dispatches Work to Client
```scala
test("Server initiates request and client responds") {
  for {
    // Create worker client
    client <- RaftClient.connect(clientConfig.copy(
      capabilities = Map("data-processor" -> "v1.0")
    ))
    sessionId <- client.createSession()
    
    // Register for server-initiated requests
    requestHandler = (request: ServerRequest) => {
      // Process the request and acknowledge immediately
      val ack = ServerRequestAck(request.requestId)
      // Process the work asynchronously after acknowledgment
      processDataAsync(request.payload)
      ack
    }
    _ <- client.registerRequestHandler("data-processor", requestHandler)
    
    // Submit work that will trigger server-initiated request
    workSubmission = SubmitWork("data-processor", testData)
    _ <- cluster.submitWork(workSubmission)
    
    // Wait for work to be dispatched and processed
    result <- client.waitForRequestCompletion(timeout = 60.seconds)
    
    _ <- assertTrue(result.isDefined)
    _ <- assertTrue(result.get.isSuccess)
    
    _ <- client.closeSession()
  } yield ()
}
```

### Expected Results
- [x] Client registers with data-processor capability
- [x] Server receives work submission
- [x] Server dispatches work to capable client
- [x] Client processes work and returns result
- [x] Work completion confirmed

## Test Scenario 5: Network Failure Recovery

### Test: Client Reconnection After Network Partition
```scala
test("Client recovers from network partition") {
  for {
    client <- RaftClient.connect(clientConfig)
    sessionId <- client.createSession()
    
    // Record initial routing ID
    initialRoutingId <- cluster.getRoutingIdForSession(sessionId)
    
    // Verify initial connectivity
    ping1 <- client.ping()
    _ <- assertTrue(ping1.isSuccess)
    
    // Simulate network partition
    _ <- cluster.simulateNetworkPartition(client.nodeId, duration = 45.seconds)
    
    // Client should detect disconnection and reconnect
    // This will trigger session continuation with new routing ID
    _ <- ZIO.sleep(60.seconds)
    
    // Verify client reconnected with same session but new routing ID
    ping2 <- client.ping()
    _ <- assertTrue(ping2.isSuccess)
    
    currentSessionId <- client.getCurrentSessionId()
    _ <- assertTrue(currentSessionId == sessionId)
    
    // Verify routing ID mapping was updated
    newRoutingId <- cluster.getRoutingIdForSession(sessionId)
    _ <- assertTrue(newRoutingId.isDefined)
    _ <- assertTrue(newRoutingId != initialRoutingId)
    
    // Verify old routing ID mapping was removed
    oldSessionMapping <- cluster.getSessionForRoutingId(initialRoutingId.get)
    _ <- assertTrue(oldSessionMapping.isEmpty)
    
    _ <- client.closeSession()
  } yield ()
}
```

### Expected Results
- [x] Initial client connectivity confirmed
- [x] Network partition simulated for 45 seconds
- [x] Client detects disconnection via heartbeat timeout
- [x] Client automatically attempts reconnection
- [x] Session continuation succeeds with routing ID update
- [x] Keep-alive tracking refreshed on server
- [x] Old routing ID mapping cleaned up
- [x] Client resumes normal operation

## Test Scenario 6: Client Connection State Management

### Test: Request Queueing in Different Connection States
```scala
test("Client queues requests correctly across connection states") {
  for {
    // Start with disconnected client
    client <- RaftClient.create(clientConfig)
    _ <- assertTrue(client.connectionState == Disconnected)
    
    // Submit request while disconnected - should queue without sending
    request1Future = client.submitCommand(TestCommand("test1"))
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(client.pendingRequestCount == 1)
    _ <- assertTrue(!request1Future.isDone) // Not sent yet
    
    // Begin connecting
    _ <- client.connect()
    _ <- assertTrue(client.connectionState == Connecting)
    
    // Submit request while connecting - should queue without sending
    request2Future = client.submitCommand(TestCommand("test2"))
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(client.pendingRequestCount == 2)
    _ <- assertTrue(!request2Future.isDone) // Not sent yet
    
    // Wait for connection establishment
    _ <- client.waitForConnection(timeout = 30.seconds)
    _ <- assertTrue(client.connectionState == Connected)
    
    // Both queued requests should now be sent automatically
    response1 <- request1Future
    response2 <- request2Future
    _ <- assertTrue(response1.isSuccess)
    _ <- assertTrue(response2.isSuccess)
    
    // Submit new request while connected - should send immediately
    response3 <- client.submitCommand(TestCommand("test3"))
    _ <- assertTrue(response3.isSuccess)
    
    _ <- client.disconnect()
  } yield ()
}
```

### Test: State Transition Behaviors
```scala
test("Client handles state transitions correctly") {
  for {
    client <- RaftClient.connect(clientConfig)
    _ <- assertTrue(client.connectionState == Connected)
    
    // Submit requests while connected
    request1Future = client.submitCommand(TestCommand("test1"))
    request2Future = client.submitCommand(TestCommand("test2"))
    
    // Simulate network partition (Connected → Connecting)
    _ <- client.simulateNetworkPartition()
    _ <- assertTrue(client.connectionState == Connecting)
    
    // Pending requests should be retained
    _ <- assertTrue(client.pendingRequestCount >= 2)
    
    // New requests should be queued
    request3Future = client.submitCommand(TestCommand("test3"))
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(!request3Future.isDone) // Queued, not sent
    
    // Restore connection (Connecting → Connected)
    _ <- client.restoreConnection()
    _ <- client.waitForConnection(timeout = 30.seconds)
    _ <- assertTrue(client.connectionState == Connected)
    
    // All requests should complete
    response1 <- request1Future
    response2 <- request2Future  
    response3 <- request3Future
    _ <- assertTrue(response1.isSuccess)
    _ <- assertTrue(response2.isSuccess)
    _ <- assertTrue(response3.isSuccess)
    
    _ <- client.disconnect()
  } yield ()
}
```

### Test: User-Initiated Disconnection
```scala
test("Client errors pending requests on user disconnection") {
  for {
    client <- RaftClient.connect(clientConfig)
    _ <- assertTrue(client.connectionState == Connected)
    
    // Submit requests while connected
    request1Future = client.submitCommand(TestCommand("test1"))
    request2Future = client.submitCommand(TestCommand("test2"))
    
    // User initiates disconnection (Connected → Disconnected)
    _ <- client.disconnect()
    _ <- assertTrue(client.connectionState == Disconnected)
    
    // Pending requests should be errored
    result1 <- request1Future.either
    result2 <- request2Future.either
    _ <- assertTrue(result1.isLeft) // Connection error
    _ <- assertTrue(result2.isLeft) // Connection error
    
    // New requests while disconnected should queue
    request3Future = client.submitCommand(TestCommand("test3"))
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(!request3Future.isDone) // Queued
    _ <- assertTrue(client.pendingRequestCount == 1)
    
    // Reconnecting should send queued requests
    _ <- client.connect()
    _ <- client.waitForConnection(timeout = 30.seconds)
    response3 <- request3Future
    _ <- assertTrue(response3.isSuccess)
    
    _ <- client.disconnect()
  } yield ()
}
```

### Expected Results
- [x] Requests queued correctly when client is Connecting or Disconnected
- [x] Requests sent immediately when client is Connected
- [x] All pending requests resent on Connecting → Connected transition
- [x] Pending requests retained on Connected → Connecting transition
- [x] Pending requests errored on Connected → Disconnected transition
- [x] New requests queue properly in Disconnected state

## Test Scenario 7: Client Stream Architecture

### Test: Unified Action Stream Processing
```scala
test("Client processes multiple event streams reactively") {
  for {
    client <- RaftClient.connect(clientConfig)
    
    // Access client's action streams for testing
    clientActions = client.actionStream
    serverRequestStream = client.serverInitiatedRequestStream
    
    // Start background stream processing
    actionFiber <- clientActions.runDrain.fork
    
    // Submit multiple concurrent user requests
    request1Future = client.submitCommand(TestCommand("test1"))
    request2Future = client.submitCommand(TestCommand("test2"))
    
    // Trigger server-initiated request
    serverRequest <- cluster.dispatchWork("test-worker", testPayload)
    
    // Verify server request appears in dedicated stream
    receivedServerRequest <- serverRequestStream.take(1).runHead
    _ <- assertTrue(receivedServerRequest.isDefined)
    _ <- assertTrue(receivedServerRequest.get.payload == testPayload)
    
    // Verify user requests complete normally
    response1 <- request1Future
    response2 <- request2Future
    _ <- assertTrue(response1.isSuccess)
    _ <- assertTrue(response2.isSuccess)
    
    // Clean up
    _ <- actionFiber.interrupt
    _ <- client.disconnect()
  } yield ()
}
```

### Test: Timer-Based Action Processing  
```scala
test("Client handles timer-based actions correctly") {
  for {
    // Create client with short intervals for testing
    testConfig = clientConfig.copy(
      keepAliveInterval = 1.second,
      connectionTimeout = 5.seconds
    )
    client <- RaftClient.create(testConfig)
    
    // Monitor action stream events
    actionEvents <- Ref.make(List.empty[String])
    actionFiber <- client.actionStream
      .tap(action => actionEvents.update(action.getClass.getSimpleName :: _))
      .runDrain
      .fork
    
    // Start connecting (should trigger TimeoutCheckAction)
    _ <- client.connect()
    _ <- client.waitForConnection(timeout = 30.seconds)
    
    // Wait for keep-alive actions
    _ <- ZIO.sleep(3.seconds)
    
    // Verify timer actions were processed
    events <- actionEvents.get
    _ <- assertTrue(events.contains("SendKeepAliveAction"))
    _ <- assertTrue(events.contains("TimeoutCheckAction"))
    _ <- assertTrue(events.contains("NetworkMessageAction")) // From session creation
    
    // Clean up
    _ <- actionFiber.interrupt
    _ <- client.disconnect()
  } yield ()
}
```

### Test: Action Stream State Management
```scala  
test("Client action processing respects connection state") {
  for {
    client <- RaftClient.create(clientConfig)
    
    // Track state-dependent action outcomes
    actionResults <- Ref.make(List.empty[String])
    
    actionFiber <- client.actionStream.mapZIO { action =>
      client.connectionState.flatMap { state =>
        actionResults.update(s"${action.getClass.getSimpleName}-${state}" :: _)
      }
    }.runDrain.fork
    
    // Test actions in different states
    
    // In Disconnected state - requests should queue
    _ <- assertTrue(client.connectionState == Disconnected)
    requestFuture1 = client.submitCommand(TestCommand("test1"))
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(!requestFuture1.isDone) // Queued, not sent
    
    // Transition to Connecting
    _ <- client.connect()
    _ <- ZIO.retry(Schedule.spaced(100.millis) && Schedule.recurs(10))(
      client.connectionState.map(_ == Connecting).orElseFail(())
    )
    
    // In Connecting state - requests should still queue
    requestFuture2 = client.submitCommand(TestCommand("test2"))  
    _ <- ZIO.sleep(100.millis)
    _ <- assertTrue(!requestFuture2.isDone) // Still queued
    
    // Wait for Connected state - should send all queued requests
    _ <- client.waitForConnection(timeout = 30.seconds)
    response1 <- requestFuture1
    response2 <- requestFuture2
    _ <- assertTrue(response1.isSuccess)
    _ <- assertTrue(response2.isSuccess)
    
    // Verify action processing results
    results <- actionResults.get
    _ <- assertTrue(results.exists(_.contains("UserClientRequestAction-Disconnected")))
    _ <- assertTrue(results.exists(_.contains("UserClientRequestAction-Connecting")))
    _ <- assertTrue(results.exists(_.contains("NetworkMessageAction-Connected")))
    
    // Clean up
    _ <- actionFiber.interrupt
    _ <- client.disconnect()
  } yield ()
}
```

### Expected Results  
- [x] Client processes multiple event streams through unified action stream
- [x] Server-initiated requests available via separate dedicated stream  
- [x] Timer-based actions (keep-alive, timeouts) integrate seamlessly
- [x] Action processing respects current connection state
- [x] Request queuing behavior works correctly with stream architecture
- [x] Stream processing is reactive and non-blocking

## Test Scenario 8: Session Timeout and Cleanup

### Test: Session Expires When Client Disconnects
```scala
test("Session cleanup after client timeout") {
  for {
    client <- RaftClient.connect(clientConfig.copy(
      sessionTimeout = 20.seconds
    ))
    sessionId <- client.createSession()
    
    // Verify session is active
    isActive1 <- cluster.isSessionActive(sessionId)
    _ <- assertTrue(isActive1)
    
    // Disconnect client without closing session
    _ <- client.disconnect()
    
    // Wait for session timeout
    _ <- ZIO.sleep(25.seconds)
    
    // Verify session has been cleaned up
    isActive2 <- cluster.isSessionActive(sessionId)
    _ <- assertTrue(!isActive2)
    
    // Attempt to continue expired session should fail
    newClient <- RaftClient.connect(clientConfig)
    continuationResult <- newClient.continueSession(sessionId).either
    _ <- assertTrue(continuationResult.isLeft)
    
    _ <- newClient.disconnect()
  } yield ()
}
```

### Expected Results
- [x] Session created and confirmed active
- [x] Client disconnects without session closure
- [x] Server detects missing heartbeats
- [x] Session expires after timeout period
- [x] Session state cleaned up from cluster
- [x] Session continuation attempts fail

## Performance Validation

### Latency Requirements
```scala
test("Performance requirements met") {
  for {
    client <- RaftClient.connect(clientConfig)
    sessionId <- client.createSession()
    
    // Measure session creation latency
    sessionLatency <- measureLatency(client.createSession())
    _ <- assertTrue(sessionLatency < 100.millis)
    
    // Measure command submission latency
    command = TestCommand("noop", Map.empty)
    commandLatency <- measureLatency(client.submitCommand(sessionId, command))
    _ <- assertTrue(commandLatency < 200.millis)
    
    // Measure read request latency
    readLatency <- measureLatency(client.readState(sessionId))
    _ <- assertTrue(readLatency < 50.millis)
    
    _ <- client.closeSession()
  } yield ()
}
```

### Throughput Requirements
```scala
test("Concurrent session handling") {
  for {
    // Create 100 concurrent clients
    clients <- ZIO.foreachPar(1 to 100)(i => 
      RaftClient.connect(clientConfig.copy(clientId = s"client-$i"))
    )
    
    // All clients create sessions simultaneously
    sessions <- ZIO.foreachPar(clients)(_.createSession())
    
    // Verify all sessions created successfully
    _ <- assertTrue(sessions.length == 100)
    _ <- assertTrue(sessions.toSet.size == 100) // All unique
    
    // Submit commands from all clients
    commands <- ZIO.foreachPar(clients.zip(sessions)) { case (client, sessionId) =>
      client.submitCommand(sessionId, TestCommand("ping", Map.empty))
    }
    
    // Verify all commands succeeded
    _ <- assertTrue(commands.forall(_.isSuccess))
    
    // Clean up
    _ <- ZIO.foreachDiscard(clients)(_.disconnect())
  } yield ()
}
```

## Cleanup and Validation

### Resource Cleanup Verification
```scala
afterAll {
  for {
    // Verify no resource leaks
    openSockets <- cluster.getOpenSocketCount()
    _ <- assertTrue(openSockets == 0)
    
    // Verify no active sessions
    activeSessions <- cluster.getActiveSessionCount()
    _ <- assertTrue(activeSessions == 0)
    
    // Stop cluster
    _ <- cluster.stop()
  } yield ()
}
```

## Success Criteria

The implementation is considered successful when all test scenarios pass consistently:

- [x] **Session Management**: Creation, continuation, and cleanup work correctly
- [x] **Command Processing**: Commands execute with proper linearizability guarantees  
- [x] **Leadership Handling**: Leader redirection and election scenarios handled
- [x] **Durability**: Sessions survive leader changes and temporary disconnections
- [x] **Server Requests**: Bidirectional communication patterns work reliably
- [x] **Error Recovery**: Network failures and timeouts handled gracefully
- [x] **Performance**: Latency and throughput requirements met
- [x] **Resource Management**: No memory or socket leaks detected

---
*Quickstart validation - 2025-09-24*
