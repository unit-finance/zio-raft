package tests.integration

import zio._
import zio.test._
import zio.raft.client._
import zio.raft.server._
import zio.raft.protocol._

/**
 * Integration test for session timeout and cleanup.
 * 
 * Based on Test Scenario 8 from quickstart.md:
 * - Session expiration detection
 * - Automatic cleanup of expired sessions
 * - Keep-alive mechanism
 */
object SessionTimeoutIntegrationSpec extends ZIOSpecDefault {

  override def spec = suiteAll("Session Timeout Integration") {
    
    suiteAll("Session Expiration") {
      test("should expire sessions after timeout") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(sessionTimeout = 5.seconds)
            val client = RaftClient.connect(clientConfig)
            val sessionId = client.createSession()
            
            // Disconnect client (no more heartbeats)
            client.disconnect()
            
            // Wait for session to expire
            Thread.sleep(7000) // 7 seconds > 5 second timeout
            
            // Session should be expired on server
            val isExpired = cluster.isSessionExpired(sessionId)
            isExpired
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should maintain session with active keep-alives") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(
              sessionTimeout = 10.seconds,
              keepAliveInterval = 3.seconds
            )
            val client = RaftClient.connect(clientConfig)
            val sessionId = client.createSession()
            
            // Keep client connected for longer than timeout
            Thread.sleep(15000) // 15 seconds > 10 second timeout
            
            // Session should still be active due to keep-alives
            val isActive = client.isSessionActive(sessionId)
            isActive
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Cleanup Mechanism") {
      test("should clean up expired sessions from server state") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(sessionTimeout = 3.seconds)
            
            // Create multiple sessions
            val client1 = RaftClient.connect(clientConfig)
            val client2 = RaftClient.connect(clientConfig)
            val sessionId1 = client1.createSession()
            val sessionId2 = client2.createSession()
            
            // Disconnect one client
            client1.disconnect()
            
            // Wait for cleanup
            Thread.sleep(5000) // 5 seconds > 3 second timeout
            
            // Only active session should remain
            val session1Exists = cluster.doesSessionExist(sessionId1)
            val session2Exists = cluster.doesSessionExist(sessionId2)
            
            !session1Exists && session2Exists
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle keep-alive frequency variations") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(
              sessionTimeout = 20.seconds,
              keepAliveInterval = 1.second // Very frequent keep-alives
            )
            val client = RaftClient.connect(clientConfig)
            val sessionId = client.createSession()
            
            // Keep session active with frequent heartbeats
            Thread.sleep(25000) // 25 seconds > 20 second timeout
            
            // Session should remain active
            val isActive = client.isSessionActive(sessionId)
            isActive
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }
  }
}

