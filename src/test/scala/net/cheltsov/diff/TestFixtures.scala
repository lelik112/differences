package net.cheltsov.diff

import java.time.Instant

import net.cheltsov.diff.DiffKeys.{DiffKey, EmptyKey, SingleKey}

private[diff] object TestFixtures {

  final case class Address(city: String, street: String)
  final case class Profile(name: String, address: Address)

  final case class TestEntity(
    id:            String,
    updatedAt:     Instant,
    amount:        BigDecimal,
    profile:       Option[Profile],
    numbers:       List[Int],
    tags:          Vector[String],
    attributes:    Map[String, Int],
    genericValues: Iterable[Int],
    additionalId:  Option[String]
  )

  val DefaultTime: Instant = Instant.parse("2026-01-01T10:00:00Z")

  val DefaultEntity: TestEntity =
    TestEntity(
      id = "operation-1",
      updatedAt = DefaultTime,
      amount = BigDecimal(100),
      profile = Some(Profile("Alice", Address("Barcelona", "Main Street"))),
      numbers = List(1, 2, 3),
      tags = Vector("card", "online"),
      attributes = Map("attempt" -> 1, "priority" -> 2),
      genericValues = List(1, 2, 3),
      additionalId = None
    )

  def diffable(
    sensitiveFields: Map[String, DiffEntity.SensitiveType] = Map.empty
  ): Diffable[TestEntity, String, String] =
    new Diffable[TestEntity, String, String] {
      override def keys(value: TestEntity): DiffKeys[String, String] = {
        val additional: DiffKey[String] =
          value.additionalId match {
            case Some(id) => SingleKey(id, "additionalId")
            case None     => EmptyKey
          }

        DiffKeys(SingleKey(value.id, "id"), additional)
      }

      override def time(value: TestEntity): Instant =
        value.updatedAt

      override def sensitiveTypes: Map[String, DiffEntity.SensitiveType] =
        sensitiveFields
    }
}
