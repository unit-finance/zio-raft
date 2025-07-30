package zio.raft

object InsertSortList:
  def empty[A](implicit ordering: Ordering[A]): InsertSortList[A] = InsertSortList(List.empty)

case class InsertSortList[A](list: List[A])(using ordering: Ordering[A]) extends Iterable[A]:

  override def iterator: Iterator[A] = list.iterator

  def withSortedInsert(a: A): InsertSortList[A] =
    if (list.isEmpty || ordering.gteq(a, list.last)) then InsertSortList(list :+ a)
    else
      val (before, after) = list.span(ordering.lteq(_, a))
      InsertSortList(before ++ (a :: after))

  override def isEmpty: Boolean = list.isEmpty
  override def last: A = list.last
  override def span(p: A => Boolean): (InsertSortList[A], InsertSortList[A]) =
    val (before, after) = list.span(p)
    (InsertSortList(before), InsertSortList(after))
