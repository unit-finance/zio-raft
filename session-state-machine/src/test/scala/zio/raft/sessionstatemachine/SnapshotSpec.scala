package zio.raft.sessionstatemachine

import zio.test.*
import zio.raft.HMap
import scodec.codecs.*
import scodec.Codec

object SnapshotSpec extends ZIOSpecDefault:

  sealed trait R
  case object R0 extends R
  given Codec[R] = provide(R0)
  given Codec[Int] = int32

  import zio.prelude.Newtype
  object UKey extends Newtype[String]
  type UKey = UKey.Type
  given HMap.KeyLike[UKey] = HMap.KeyLike.forNewtype(UKey)

  // Bring session codecs into scope
  import zio.raft.sessionstatemachine.Codecs.{sessionMetadataCodec, requestIdCodec, pendingServerRequestCodec}

  type UserSchema = ("u", UKey, Int) *: EmptyTuple
  type CombinedSchema = Tuple.Concat[SessionSchema[R, String], UserSchema]

  // Minimal state machine to exercise ScodecSerialization
  import zio.raft.Command
  import zio.prelude.State

  sealed trait DCmd extends Command
  object DCmd:
    case object Noop extends DCmd:
      type Response = Unit

  class TestMachine extends zio.raft.StateMachine[HMap[CombinedSchema], DCmd]
      with ScodecSerialization[R, String, UserSchema]:
    override val codecs: HMap.TypeclassMap[Schema, Codec] = summon
    def emptyState: HMap[CombinedSchema] = HMap.empty
    def apply(command: DCmd): State[HMap[CombinedSchema], command.Response] =
      State.succeed(().asInstanceOf[command.Response])
    def shouldTakeSnapshot(
      lastSnapshotIndex: zio.raft.Index,
      lastSnapshotSize: Long,
      commitIndex: zio.raft.Index
    ): Boolean = false

  def spec = suite("SnapshotSpec")(
    test("snapshot then restore yields identical HMap") {
      val tm = new TestMachine {}
      val s = HMap.empty[CombinedSchema]
        .updated["u"](UKey("1"), 42)
      val restored = tm.restoreFromSnapshot(tm.takeSnapshot(s))
      assertZIO(restored.map { r =>
        val rr = r.asInstanceOf[HMap[CombinedSchema]]
        rr.get["u"](UKey("1")).contains(42)
      })(Assertion.isTrue)
    }
  )
end SnapshotSpec
