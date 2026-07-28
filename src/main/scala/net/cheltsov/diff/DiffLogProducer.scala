package net.cheltsov.diff

import java.time.Instant

import zio.prelude.ForEachOps
import zio.{UIO, ZIO, ZIOAspect}

import net.cheltsov.diff.DiffEntity.DiffDetail
import net.cheltsov.diff.DiffEntity.SensitiveType.Per

trait DiffLogProducer {
  def logDiffs[T <: Product, M, A](left: IterableOnce[T], right: IterableOnce[T], scope: DiffScope)(implicit
    d: Diffable[T, M, A]
  ): UIO[Unit]
}

object DiffLogProducer {
  val single: DiffLogProducer =
    new DiffLogProducer {

      private val service = DiffService.single

      private def annotateDetail(
        detailed: DiffEntity.Detailed
      ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] = {
        import detailed.detail._

        (detailed.detail match {
          case DiffDetail(_, Some(Per), Some(_), None) =>
            ZIOAspect.annotated("leftValue" -> "Some(hidden_per)", "rightValue" -> "None")
          case DiffDetail(_, Some(Per), None, Some(_)) =>
            ZIOAspect.annotated("leftValue" -> "None", "rightValue" -> "Some(hidden_per)")
          case DiffDetail(_, Some(Per), _, _)          =>
            ZIOAspect.annotated("leftValue" -> "hidden_per", "rightValue" -> "hidden_per")
          case _                                       =>
            ZIOAspect.annotated("leftValue" -> leftValue.toString, "rightValue" -> rightValue.toString)
        }) @@
          ZIOAspect.annotated("fieldName" -> fieldName) @@
          annotateLeftTime(detailed.leftTime) @@
          annotateRightTime(detailed.rightTime)
      }

      private def annotateLeftTime(time: Instant): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any]  =
        ZIOAspect.annotated("leftTime" -> time.toString)
      private def annotateRightTime(time: Instant): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
        ZIOAspect.annotated("rightTime" -> time.toString)

      override def logDiffs[T <: Product, M, A](left: IterableOnce[T], right: IterableOnce[T], scope: DiffScope)(
        implicit d: Diffable[T, M, A]
      ): UIO[Unit] =
        service
          .diffEntities(left.iterator.toList, right.iterator.toList, scope)
          .flatMap(_.forEach { entity =>
            val annotations = entity.diff match {
              case detailed: DiffEntity.Detailed                  => annotateDetail(detailed)
              case DiffEntity.MissingOnLeft(rightTime)            => annotateRightTime(rightTime)
              case DiffEntity.MissingOnRight(leftTime)            => annotateLeftTime(leftTime)
              case DiffEntity.DuplicatesOnLeft(leftTime, count)   =>
                annotateLeftTime(leftTime) @@ ZIOAspect.annotated("leftDuplicateCount" -> count.toString)
              case DiffEntity.DuplicatesOnRight(rightTime, count) =>
                annotateRightTime(rightTime) @@ ZIOAspect.annotated("rightDuplicateCount" -> count.toString)
            }

            ZIO.logWarning(s"Diff") @@
              annotations @@
              entity.diffKeys.main.log @@
              entity.diffKeys.additional.log @@
              ZIOAspect.annotated("scope" -> scope.entryName, "diffType" -> entity.diff.diffType)
          })
          .unit

    }
}
