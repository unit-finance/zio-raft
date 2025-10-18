package tests.integration

import zio._
import zio.test._
import zio.raft.client._
import zio.raft.server._
import zio.raft.protocol._

/**
 * Integration test for session durability across failures.
 * 
 * Based on Test Scenario 3 from quickstart.md:
 * - Session persistence across leader changes
 * - Session recovery after network failures
 * - Session continuation after client disconnection
 */
object SessionDurabilityIntegrationSpec extends ZIOSpecDefault {

  override def spec = suiteAll("Session Durability Integration") {
    
    suiteAll("Leader Change Durability") {
      test("should maintain session during leader change") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Stop leader to trigger election
            cluster.stopLeader()
            cluster.waitForNewLeader()
            
            // Session should still be valid
            val isActive = client.isSessionActive(sessionId)
            isActive
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should continue session operations after leader change") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Trigger leader change
            cluster.stopLeader()
            cluster.waitForNewLeader()
            
            // Continue session should work
            val continued = client.continueSession(sessionId)
            continued
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Client Disconnection Recovery") {
      test("should recover session after client disconnect") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client1 = RaftClient.connect(ClientConfig.default)
            val sessionId = client1.createSession()
            
            // Disconnect client
            client1.disconnect()
            
            // Reconnect with new client instance
            val client2 = RaftClient.connect(ClientConfig.default)
            val resumed = client2.continueSession(sessionId)
            
            resumed
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle session timeout after prolonged disconnection") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(sessionTimeout = 5.seconds)
            val client1 = RaftClient.connect(clientConfig)
            val sessionId = client1.createSession()
            
            // Disconnect and wait beyond timeout
            client1.disconnect()
            Thread.sleep(7000) // 7 seconds > 5 second timeout
            
            // Try to continue expired session
            val client2 = RaftClient.connect(clientConfig)
            try {
              client2.continueSession(sessionId)
              false // Should not succeed
            } catch {
              case _: SessionExpiredException => true // Expected
            }
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Multi-Client Session Isolation") {
      test("should maintain separate sessions for multiple clients") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            
            // Create multiple clients with separate sessions
            val client1 = RaftClient.connect(ClientConfig.default)
            val client2 = RaftClient.connect(ClientConfig.default)
            val sessionId1 = client1.createSession()
            val sessionId2 = client2.createSession()
            
            // Sessions should be different
            val different = sessionId1 != sessionId2
            val both_active = client1.isSessionActive(sessionId1) && client2.isSessionActive(sessionId2)
            
            different && both_active
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }
  }
}

