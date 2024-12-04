package zio.lmdb

import org.lmdbjava.Txn

class TransactionScope(val txn: Txn[Array[Byte]]):

end TransactionScope
