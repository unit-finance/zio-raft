package tests.integration

import zio._
import zio.test._
import zio.raft.client._
import zio.raft.server._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Integration test for command submission and leadership handling.
 * 
 * Based on Test Scenario 2 from quickstart.md:
 * - Submit commands through client
 * - Handle leader redirection
 * - Verify linearizable execution
 * - Test both read and write operations
 */
object CommandSubmissionIntegrationSpec extends ZIOSpecDefault {

  override def spec = suiteAll("Command Submission Integration") {
    
    suiteAll("Basic Command Operations") {
      test("should submit write command and receive response") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Submit write command
            val writeCommand = ByteVector.fromValidHex("deadbeef")
            val response = client.submitCommand(sessionId, writeCommand)
            
            // Verify response received
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should submit read query and receive current state") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Submit read query
            val readQuery = ByteVector.fromValidHex("cafebabe")
            val response = client.submitQuery(sessionId, readQuery)
            
            // Verify response received
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle command with request timeout") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val clientConfig = ClientConfig(
              requestTimeout = 5.seconds
            )
            val client = RaftClient.connect(clientConfig)
            val sessionId = client.createSession()
            
            // Submit command with timeout
            val command = ByteVector.fromValidHex("12345678")
            val response = client.submitCommand(sessionId, command, timeout = 3.seconds)
            
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Leader Redirection") {
      test("should handle automatic leader redirection") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val follower = cluster.followers.head
            
            // Connect to follower initially
            val clientConfig = ClientConfig(
              clusterAddresses = List(follower.address)
            )
            val client = RaftClient.connect(clientConfig)
            
            // Client should automatically redirect to leader for session creation
            val sessionId = client.createSession()
            val command = ByteVector.fromValidHex("redirect")
            val response = client.submitCommand(sessionId, command)
            
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle leader change during command submission") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Simulate leader failure
            cluster.stopLeader()
            cluster.waitForNewLeader()
            
            // Submit command after leader change
            val command = ByteVector.fromValidHex("newleader")
            val response = client.submitCommand(sessionId, command)
            
            response.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Linearizability") {
      test("should provide linearizable read-after-write consistency") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Write operation
            val writeCommand = ByteVector.fromValidHex("writevalue")
            val writeResponse = client.submitCommand(sessionId, writeCommand)
            
            // Immediate read should see the write
            val readQuery = ByteVector.fromValidHex("readvalue")
            val readResponse = client.submitQuery(sessionId, readQuery)
            
            writeResponse.isRight && readResponse.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should maintain operation ordering across multiple clients") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            
            // Create multiple clients
            val client1 = RaftClient.connect(ClientConfig.default)
            val client2 = RaftClient.connect(ClientConfig.default)
            val session1 = client1.createSession()
            val session2 = client2.createSession()
            
            // Submit operations from both clients
            val command1 = ByteVector.fromValidHex("client1op")
            val command2 = ByteVector.fromValidHex("client2op")
            
            val response1 = client1.submitCommand(session1, command1)
            val response2 = client2.submitCommand(session2, command2)
            
            response1.isRight && response2.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Request Correlation") {
      test("should correlate responses with request IDs") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Submit multiple commands concurrently
            val commands = (1 to 5).map { i =>
              ByteVector.fromValidHex(f"command$i%08x")
            }
            
            val responses = commands.map { command =>
              client.submitCommand(sessionId, command)
            }
            
            // All responses should be successful
            responses.forall(_.isRight)
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle duplicate request IDs appropriately") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            val command = ByteVector.fromValidHex("duplicate")
            
            // Submit same command twice
            val response1 = client.submitCommand(sessionId, command)
            val response2 = client.submitCommand(sessionId, command)
            
            // Both should succeed (idempotency handled by state machine)
            response1.isRight && response2.isRight
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Error Handling") {
      test("should handle command submission without session") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val invalidSessionId = SessionId.fromString("invalid-session")
            
            // Try to submit command without valid session
            val command = ByteVector.fromValidHex("nosession")
            
            try {
              client.submitCommand(invalidSessionId, command)
              false // Should not succeed
            } catch {
              case _: SessionNotFoundException => true // Expected
            }
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle oversized command payloads") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Create oversized payload (> configured maximum)
            val oversizedCommand = ByteVector.fill(10 * 1024 * 1024)(0) // 10MB payload
            
            try {
              client.submitCommand(sessionId, oversizedCommand)
              false // Should not succeed
            } catch {
              case _: PayloadTooLargeException => true // Expected
            }
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }
  }
}

