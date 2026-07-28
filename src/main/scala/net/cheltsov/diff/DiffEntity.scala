package net.cheltsov.diff

import java.time.Instant

import net.cheltsov.diff.DiffEntity.Difference

import enumeratum.EnumEntry.Uppercase
import enumeratum.{Enum, EnumEntry}

final case class DiffEntity[M, A](
  diffKeys: DiffKeys[M, A],
  scope:    DiffScope,
  diff:     Difference
)

object DiffEntity {

  sealed trait Difference {
    def diffType: String  = this.getClass.getSimpleName
    def widen: Difference = this
  }
  final case class MissingOnLeft(rightTime: Instant) extends Difference
  final case class MissingOnRight(leftTime: Instant)                                   extends Difference
  final case class DuplicatesOnLeft(leftTime: Instant, count: Int)                     extends Difference
  final case class DuplicatesOnRight(rightTime: Instant, count: Int)                   extends Difference
  final case class Detailed(leftTime: Instant, rightTime: Instant, detail: DiffDetail) extends Difference

  sealed trait SensitiveType extends EnumEntry with Uppercase
  object SensitiveType       extends Enum[SensitiveType] {
    case object Per extends SensitiveType
    override def values: IndexedSeq[SensitiveType] = findValues
  }

  final case class DiffDetail(
    fieldName:     String,
    sensitiveType: Option[SensitiveType],
    leftValue:     Any,
    rightValue:    Any
  )

}
