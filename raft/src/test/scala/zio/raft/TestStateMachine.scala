package zio.raft


sealed trait TestCommands extends Command:
  type Response = Int
case object Increase extends TestCommands
case object Get extends TestCommands

case class TestStateMachine(state: Int) extends StateMachine[TestCommands]:
  def apply(command: TestCommands): (command.Response, StateMachine[TestCommands]) = 
    command match
      case Increase => (state + 1, TestStateMachine(state + 1))
      case Get => (state, TestStateMachine(state))

object TestStateMachine:
    def make = TestStateMachine(0)
        