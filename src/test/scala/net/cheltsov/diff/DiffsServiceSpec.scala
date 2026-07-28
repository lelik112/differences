package net.cheltsov.diff

import java.time.Instant

import zio.test.{ZIOSpecDefault, assertTrue}

import net.cheltsov.diff.DiffEntity._
import net.cheltsov.diff.TestFixtures._

object DiffsServiceSpec extends ZIOSpecDefault {

  private val service = DiffService.single

  private implicit val entityDiffable: Diffable[TestEntity, String, String] =
    diffable()

  override def spec =
    suite("DiffsService")(
      test("returns no differences for equal entities") {
        for {
          result <- service.diffEntities(List(DefaultEntity), List(DefaultEntity), DiffScope.Common)
        } yield assertTrue(result.isEmpty)
      },
      test("reports an entity missing on the left") {
        for {
          result <- service.diffEntities(Nil, List(DefaultEntity), DiffScope.Common)
        } yield assertTrue(
          result.map(_.diff) == List(MissingOnLeft(DefaultTime))
        )
      },
      test("reports an entity missing on the right") {
        for {
          result <- service.diffEntities(List(DefaultEntity), Nil, DiffScope.Common)
        } yield assertTrue(
          result.map(_.diff) == List(MissingOnRight(DefaultTime))
        )
      },
      test("uses the additional key as part of entity identity") {
        val left  = DefaultEntity.copy(additionalId = Some("left-reference"))
        val right = DefaultEntity.copy(additionalId = Some("right-reference"))

        for {
          result <- service.diffEntities(List(left), List(right), DiffScope.Common)
        } yield assertTrue(
          result.exists(_.diff == MissingOnRight(DefaultTime)),
          result.exists(_.diff == MissingOnLeft(DefaultTime)),
          !result.exists(_.diff.isInstanceOf[Detailed])
        )
      },
      test("reports extra duplicate entities on both sides") {
        val later          = DefaultTime.plusSeconds(10)
        val leftDuplicate  = DefaultEntity.copy(updatedAt = later, amount = BigDecimal(110))
        val rightDuplicate = DefaultEntity.copy(updatedAt = later, amount = BigDecimal(120))

        for {
          result <- service.diffEntities(
                      List(DefaultEntity, leftDuplicate),
                      List(DefaultEntity, rightDuplicate),
                      DiffScope.Common
                    )
        } yield assertTrue(
          result.exists(_.diff == DuplicatesOnLeft(DefaultTime, count = 1)),
          result.exists(_.diff == DuplicatesOnRight(DefaultTime, count = 1))
        )
      },
      test("reports a changed top-level field") {
        val right = DefaultEntity.copy(amount = BigDecimal(150))

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
          details = result.collect { case DiffEntity(_, _, detailed: Detailed) => detailed.detail }
        } yield assertTrue(
          details == List(DiffDetail("amount", None, BigDecimal(100), BigDecimal(150)))
        )
      },
      test("reports the full path of a changed nested field") {
        val right = DefaultEntity.copy(
          profile = DefaultEntity.profile.map(profile => profile.copy(address = profile.address.copy(city = "Tallinn")))
        )

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
          fields  = result.collect { case DiffEntity(_, _, Detailed(_, _, detail)) => detail.fieldName }
        } yield assertTrue(fields == List("profile.address.city"))
      },
      test("reports Some versus None at the option field") {
        val right = DefaultEntity.copy(profile = None)

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
          details = result.collect { case DiffEntity(_, _, Detailed(_, _, detail)) => detail }
        } yield assertTrue(
          details.size == 1,
          details.headOption.exists(_.fieldName == "profile"),
          details.headOption.exists(_.leftValue == DefaultEntity.profile),
          details.headOption.exists(_.rightValue == None)
        )
      },
      test("treats collections as multisets and ignores element order") {
        val right = DefaultEntity.copy(
          numbers = DefaultEntity.numbers.reverse,
          tags = DefaultEntity.tags.reverse,
          attributes = DefaultEntity.attributes.toList.reverse.toMap
        )

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
        } yield assertTrue(result.isEmpty)
      },
      test("ignores the concrete collection type") {
        val right = DefaultEntity.copy(genericValues = Vector(3, 2, 1))

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
        } yield assertTrue(result.isEmpty)
      },
      test("detects different multiplicities in a collection") {
        val left  = DefaultEntity.copy(numbers = List(1, 1, 2))
        val right = DefaultEntity.copy(numbers = List(1, 2, 2))

        for {
          result <- service.diffEntities(List(left), List(right), DiffScope.Common)
          details = result.collect { case DiffEntity(_, _, Detailed(_, _, detail)) => detail }
        } yield assertTrue(
          details.map(_.fieldName) == List("numbers"),
          details.headOption.exists(_.leftValue == List(1, 1, 2)),
          details.headOption.exists(_.rightValue == List(1, 2, 2))
        )
      },
      test("reports a Vector difference as one field instead of traversing collection internals") {
        val right = DefaultEntity.copy(tags = Vector("card", "offline"))

        for {
          result <- service.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
          details = result.collect { case DiffEntity(_, _, Detailed(_, _, detail)) => detail }
        } yield assertTrue(
          details.map(_.fieldName) == List("tags"),
          details.headOption.exists(_.leftValue == DefaultEntity.tags),
          details.headOption.exists(_.rightValue == right.tags)
        )
      },
      test("uses the first entity in a duplicate group for detailed comparison") {
        val changedTime = Instant.parse("2026-01-01T11:00:00Z")
        val duplicate   = DefaultEntity.copy(updatedAt = changedTime, amount = BigDecimal(999))

        for {
          result <- service.diffEntities(
                      List(DefaultEntity, duplicate),
                      List(DefaultEntity),
                      DiffScope.Common
                    )
          details = result.collect { case DiffEntity(_, _, detailed: Detailed) => detailed }
        } yield assertTrue(
          result.exists(_.diff == DuplicatesOnLeft(DefaultTime, count = 1)),
          details.isEmpty
        )
      }
    )
}
