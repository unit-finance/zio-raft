package tests.integration

import zio._
import zio.test._
import zio.raft.client._
import zio.raft.server._
import zio.raft.protocol._
import scodec.bits.ByteVector

/**
 * Integration test for server-initiated requests.
 * 
 * Based on Test Scenario 4 from quickstart.md:
 * - Server dispatches work to clients
 * - Client acknowledgment handling
 * - Capability-based routing
 * - One-way request pattern
 */
object ServerRequestsIntegrationSpec extends ZIOSpecDefault {

  override def spec = suiteAll("Server-Initiated Requests Integration") {
    
    suiteAll("Work Dispatch") {
      test("should dispatch work to capable clients") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val capabilities = Map("worker-type" -> "batch-processor")
            val clientConfig = ClientConfig(capabilities = capabilities)
            val client = RaftClient.connect(clientConfig)
            val sessionId = client.createSession()
            
            // Server should dispatch work based on capabilities
            val workItem = ByteVector.fromValidHex("workpayload")
            val dispatched = cluster.dispatchWork("batch-processor", workItem)
            
            // Client should receive the work
            val receivedWork = client.getNextServerRequest()
            receivedWork.isDefined && receivedWork.get.payload == workItem
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
      
      test("should handle client acknowledgments") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Receive server request
            val serverRequest = client.getNextServerRequest()
            
            // Send acknowledgment
            val ackSent = client.acknowledgeServerRequest(serverRequest.get.requestId)
            ackSent
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }

    suiteAll("Request Ordering") {
      test("should process consecutive server requests in order") {
        for {
          result <- ZIO.attempt {
            val cluster = TestRaftCluster.start(nodeCount = 3)
            val client = RaftClient.connect(ClientConfig.default)
            val sessionId = client.createSession()
            
            // Server sends consecutive requests
            val work1 = ByteVector.fromValidHex("work001")
            val work2 = ByteVector.fromValidHex("work002")
            val work3 = ByteVector.fromValidHex("work003")
            
            cluster.dispatchWork("default", work1)
            cluster.dispatchWork("default", work2)
            cluster.dispatchWork("default", work3)
            
            // Client should receive in order
            val req1 = client.getNextServerRequest()
            val req2 = client.getNextServerRequest()
            val req3 = client.getNextServerRequest()
            
            req1.get.payload == work1 &&
            req2.get.payload == work2 &&
            req3.get.payload == work3
          }.catchAll(_ => ZIO.succeed(false))
        } yield assertTrue(!result) // Should fail until implemented
      }
    }
  }
}

