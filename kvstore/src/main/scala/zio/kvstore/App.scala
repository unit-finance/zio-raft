package zio.kvstore

import zio.ZIO

object KVStoreApp extends zio.ZIOAppDefault:
  override def run =
    // Simplified demo - just show that KVStateMachine compiles
    // Full integration would require session management setup
    ZIO.succeed(println("KVStore with SessionStateMachine - compilation successful")).exitCode
