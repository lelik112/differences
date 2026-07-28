package net.cheltsov.diff

import java.time.Instant

import net.cheltsov.diff.DiffEntity.SensitiveType

trait Diffable[T, M, A] {
  def keys(value: T): DiffKeys[M, A]
  def time(value: T): Instant
  def sensitiveTypes: Map[String, SensitiveType]
}

object Diffable {

  implicit class DiffableOps[T, M, A](private val value: T)(implicit d: Diffable[T, M, A]) {
    def keys: DiffKeys[M, A] =
      d.keys(value)

    def time: Instant =
      d.time(value)

    private def pathsOverlap(left: String, right: String): Boolean =
      left == right || left.startsWith(right + ".") || right.startsWith(left + ".")

    def sensitiveType(field: String): Option[SensitiveType] =
      d.sensitiveTypes.collectFirst {
        case (configuredField, sensitiveType) if pathsOverlap(field, configuredField) => sensitiveType
      }
  }

}
