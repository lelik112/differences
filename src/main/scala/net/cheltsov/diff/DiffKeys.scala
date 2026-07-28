package net.cheltsov.diff

import zio.ZIOAspect

import net.cheltsov.diff.DiffKeys.{DiffKey, SingleKey}

final case class DiffKeys[M, A](main: SingleKey[M], additional: DiffKey[A])

object DiffKeys {
  sealed trait DiffKey[+T] {
    val log: ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
      this match {
        case EmptyKey             => ZIOAspect.identity
        case single: SingleKey[_] => ZIOAspect.annotated(single.name, single.value.toString)
      }
  }

  final object EmptyKey                                 extends DiffKey[Nothing]
  final case class SingleKey[T](value: T, name: String) extends DiffKey[T]
}
