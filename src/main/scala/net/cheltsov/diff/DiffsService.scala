package net.cheltsov.diff

import zio.metrics.Metric
import zio.prelude.ForEachOps
import zio.{UIO, ZIO}

import net.cheltsov.diff.DiffEntity._
import net.cheltsov.diff.Diffable.DiffableOps

private[diff] trait DiffsService {
  def diffEntities[T <: Product, M, A](left: List[T], right: List[T], scope: DiffScope)(implicit
    d: Diffable[T, M, A]
  ): UIO[List[DiffEntity[M, A]]]
}

private[diff] object DiffService {

  private val EntityCounter           = Metric.counter("diff_entity_total")
  private val ManyEntityCounter       = Metric.counter("diff_entity_many_total")
  private val OmittedEntityCounter    = Metric.counter("diff_entity_omitted_total")
  private val SameEntityCounter       = Metric.counter("diff_entity_same_total")
  private val DiffFieldsEntityCounter = Metric.counter("entity_same_diff_fields_total")

  private val ScopeLabelKey    = "diff_scope"
  private val SystemLabelKey   = "system"
  private val EqualityLabelKey = "equality"
  private val FieldLabelKey    = "field"

  private val LeftLabelValue     = "left"
  private val RightLabelValue    = "right"
  private val EqualLabelValue    = "Equal"
  private val NotEqualLabelValue = "NotEqual"

  val single: DiffsService =
    new DiffsService {

      private def frequencies(values: IterableOnce[_]): Map[Any, Int] =
        values.iterator
          .map(value => value: Any)
          .toList
          .groupMapReduce(identity)(_ => 1)(_ + _)

      private def technicallyEqual(left: Any, right: Any): Boolean =
        (left, right) match {
          case (Some(l), Some(r)) =>
            technicallyEqual(l, r)

          case (l: IterableOnce[_], r: IterableOnce[_]) =>
            frequencies(l) == frequencies(r)

          case _ =>
            left == right
        }

      private def isCollectionLike(value: Any): Boolean =
        value match {
          case Some(_: IterableOnce[_]) => true
          case _: Option[_]             => false
          case _: IterableOnce[_]       => true
          case _                        => false
        }

      private def notEqualFields(fieldName: String, left: Product, right: Product): List[(String, Any, Any)] = {
        val leftNames  = left.productElementNames.toList
        val rightNames = right.productElementNames.toList

        if (leftNames == rightNames)
          (leftNames zip left.productIterator zip right.productIterator)
            .foldLeft(List.empty[(String, Any, Any)]) {
              case (acc, ((_, l), r)) if technicallyEqual(l, r)                              =>
                acc
              case (acc, ((name, l), r)) if isCollectionLike(l) || isCollectionLike(r)       =>
                acc :+ ((s"$fieldName.$name", l, r))
              case (acc, ((name, Some(l: Product)), Some(r: Product))) if l.productArity > 0 =>
                acc ++ notEqualFields(s"$fieldName.$name", l, r)
              case (acc, ((name, l: Product), r: Product)) if l.productArity > 0             =>
                acc ++ notEqualFields(s"$fieldName.$name", l, r)
              case (acc, ((name, l), r))                                                     =>
                acc ++ List((s"$fieldName.$name", l, r))
            }
        else
          List((fieldName, left, right))
      }

      private def clean(fieldName: String): String = fieldName.dropWhile(_ == '.')

      override def diffEntities[T <: Product, M, A](left: List[T], right: List[T], scope: DiffScope)(implicit
        d: Diffable[T, M, A]
      ): UIO[List[DiffEntity[M, A]]] = {

        def incrementMetric(iterable: Iterable[_], counter: Metric.Counter[Long], systemLabelValue: String): UIO[Unit] =
          counter
            .tagged(ScopeLabelKey, scope.entryName)
            .tagged(SystemLabelKey, systemLabelValue)
            .incrementBy(iterable.size.toLong)
            .when(iterable.nonEmpty)
            .unit

        val leftMap  = left.groupMap(_.keys)(identity)
        val rightMap = right.groupMap(_.keys)(identity)

        val leftDiffs = leftMap.values.toList.forEachFlatten {
          case leftEntity :: tail =>
            val manyDiff =
              incrementMetric(tail, ManyEntityCounter, LeftLabelValue)
                .as(leftEntity.keys -> DuplicatesOnLeft(leftEntity.time, tail.size).widen)
                .when(tail.nonEmpty)
                .map(_.toList)

            val omittedDiff =
              OmittedEntityCounter
                .tagged(ScopeLabelKey, scope.entryName)
                .tagged(SystemLabelKey, RightLabelValue)
                .increment
                .as(leftEntity.keys -> MissingOnRight(leftEntity.time).widen)
                .when(!rightMap.contains(leftEntity.keys))
                .map(_.toList)

            val detailedDiffs =
              rightMap
                .get(leftEntity.keys)
                .flatMap(_.headOption)
                .toList
                .forEachFlatten { rightEntity =>
                  val details =
                    notEqualFields("", leftEntity, rightEntity)
                      .map { case (name, m, t) => DiffDetail(clean(name), leftEntity.sensitiveType(clean(name)), m, t) }

                  SameEntityCounter
                    .tagged(ScopeLabelKey, scope.entryName)
                    .tagged(EqualityLabelKey, if (details.isEmpty) EqualLabelValue else NotEqualLabelValue)
                    .increment *>
                    details.forEach { detail =>
                      DiffFieldsEntityCounter
                        .tagged(ScopeLabelKey, scope.entryName)
                        .tagged(FieldLabelKey, detail.fieldName)
                        .increment
                        .as(leftEntity.keys -> Detailed(leftEntity.time, rightEntity.time, detail).widen)
                    }
                }

            for {
              manyDiff      <- manyDiff
              omittedDiff   <- omittedDiff
              detailedDiffs <- detailedDiffs
            } yield manyDiff ++ omittedDiff ++ detailedDiffs

          case Nil => ZIO.succeed(Nil)
        }

        val rightDiffs = rightMap.values.toList.forEachFlatten {
          case rightEntity :: tail =>
            val manyDiff =
              incrementMetric(tail, ManyEntityCounter, RightLabelValue)
                .as(rightEntity.keys -> DuplicatesOnRight(rightEntity.time, tail.size).widen)
                .when(tail.nonEmpty)
                .map(_.toList)

            val omittedDiff =
              OmittedEntityCounter
                .tagged(ScopeLabelKey, scope.entryName)
                .tagged(SystemLabelKey, LeftLabelValue)
                .increment
                .as(rightEntity.keys -> MissingOnLeft(rightEntity.time).widen)
                .when(!leftMap.contains(rightEntity.keys))
                .map(_.toList)

            for {
              manyDiff    <- manyDiff
              omittedDiff <- omittedDiff
            } yield manyDiff ++ omittedDiff

          case Nil => ZIO.succeed(Nil)
        }

        for {
          _          <- incrementMetric(leftMap, EntityCounter, LeftLabelValue)
          _          <- incrementMetric(rightMap, EntityCounter, RightLabelValue)
          leftDiffs  <- leftDiffs
          rightDiffs <- rightDiffs
        } yield (leftDiffs ++ rightDiffs).map { case (keys, diff) => DiffEntity(keys, scope, diff) }

      }

    }
}
