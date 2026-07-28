package net.cheltsov.diff

import enumeratum.{Enum, EnumEntry}

sealed trait DiffScope extends EnumEntry

object DiffScope extends Enum[DiffScope] {

  case object Common extends DiffScope

  override def values: IndexedSeq[DiffScope] = findValues
}
