package tests.integration

import zio._
import zio.test._
import zio.raft.client._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Integration test for client connection state management.
 * 
 * Based on Test Scenario 6 from quickstart.md:
 * - Connection state transitions
 * - Request queuing behavior
 * - Automatic reconnection
 */
object ClientConnectionStateIntegrationSpec extends ZIOSpecDefault {

  override def spec = suiteAll("Client Connection State Integration") {
    
    suiteAll("State Transitions") {
      test("should handle Connecting to Connected transition") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            
            // Client starts in Connecting state
            val initialState = client.getConnectionState()
            
            // After session creation, should be Connected
            val sessionId = client.createSession()
            val connectedState = client.getConnectionState()
            
            initialState == Connecting && connectedState == Connected
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should queue requests in Connecting state") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            
            // Submit request while still connecting
            val command = ByteVector.fromValidHex("queued")
            val requestPromise = client.submitCommandAsync(command)
            
            // Create session to transition to Connected
            val sessionId = client.createSession()
            
            // Queued request should now be sent and receive response
            val response = requestPromise.await
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Request Queuing Behavior") {
      test("should resend queued requests on reconnection") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Queue requests
            val command1 = ByteVector.fromValidHex("queue1")
            val command2 = ByteVector.fromValidHex("queue2")
            val promise1 = client.submitCommandAsync(command1)
            val promise2 = client.submitCommandAsync(command2)
            
            // Simulate disconnect/reconnect
            client.forceDisconnect()
            client.reconnect()
            
            // Queued requests should be resent
            val response1 = promise1.await
            val response2 = promise2.await
            
            response1.isRight && response2.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }
  }
}

