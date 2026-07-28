package net.cheltsov.diff

import zio.test.{ZIOSpecDefault, assertTrue}

import net.cheltsov.diff.DiffEntity.SensitiveType.Per
import net.cheltsov.diff.DiffEntity.{Detailed, SensitiveType}
import net.cheltsov.diff.Diffable.DiffableOps
import net.cheltsov.diff.TestFixtures._

object DiffableSpec extends ZIOSpecDefault {

  override def spec =
    suite("Diffable sensitive fields")(
      test("matches an exact field path") {
        implicit val configured: Diffable[TestEntity, String, String] =
          diffable(Map("profile.address.city" -> Per))

        assertTrue(DefaultEntity.sensitiveType("profile.address.city").contains(Per))
      },
      test("a sensitive parent path protects its nested fields") {
        implicit val configured: Diffable[TestEntity, String, String] =
          diffable(Map("profile.address" -> Per))

        assertTrue(DefaultEntity.sensitiveType("profile.address.city").contains(Per))
      },
      test("a sensitive child path protects a parent-level difference") {
        implicit val configured: Diffable[TestEntity, String, String] =
          diffable(Map("profile.address.city" -> Per))

        assertTrue(DefaultEntity.sensitiveType("profile.address").contains(Per))
      },
      test("adds the sensitive type to a generated field difference") {
        implicit val configured: Diffable[TestEntity, String, String] =
          diffable(Map("profile.address" -> Per))

        val right = DefaultEntity.copy(
          profile = DefaultEntity.profile.map(profile => profile.copy(address = profile.address.copy(city = "Tallinn")))
        )

        for {
          result        <- DiffService.single.diffEntities(List(DefaultEntity), List(right), DiffScope.Common)
          sensitiveTypes = result.collect { case DiffEntity(_, _, Detailed(_, _, detail)) =>
                             detail.sensitiveType
                           }
        } yield assertTrue(sensitiveTypes == List(Some(SensitiveType.Per)))
      },
      test("does not mark an unrelated path as sensitive") {
        implicit val configured: Diffable[TestEntity, String, String] =
          diffable(Map("profile.address.city" -> Per))

        assertTrue(DefaultEntity.sensitiveType("amount").isEmpty)
      }
    )
}
