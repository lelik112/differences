# Entity Differences

A small Scala library for comparing entities produced by two implementations of the same business flow.

It is useful during migrations from a legacy system to a new service: both implementations can run in parallel, their results can be matched by business keys, and any differences can be logged and measured without exposing configured sensitive values.

This repository is an anonymised and simplified extraction of a production-oriented comparison mechanism. It contains no company-specific models, endpoints, or infrastructure configuration.

## What it does

For two collections of the same entity type, the library:

- matches entities by a main key and an optional additional key;
- detects entities missing from either side;
- detects duplicate entities with the same key;
- recursively compares case-class fields;
- reports full paths for nested differences, such as `profile.address.city`;
- compares collections as multisets, ignoring order but preserving element counts;
- marks configured fields as sensitive so their values can be hidden in logs;
- records ZIO metrics for compared, missing, duplicate, and changed entities.

## Main abstractions

### `Diffable`

A type class that describes how a domain entity participates in comparison:

```scala
trait Diffable[T, M, A] {
  def keys(value: T): DiffKeys[M, A]
  def time(value: T): Instant
  def sensitiveTypes: Map[String, SensitiveType]
}
```

The comparison engine remains independent of any concrete business model. A domain module only needs to provide:

- the matching keys;
- the entity timestamp;
- paths of fields whose values must not be written to logs.

### `DiffKeys`

```scala
final case class DiffKeys[M, A](
  main: SingleKey[M],
  additional: DiffKey[A]
)
```

The main and additional keys form the identity used to group and match entities. The additional key may be `EmptyKey`.

Key values are added to log annotations by `DiffLogProducer`, so a `Diffable` instance must only use values that are safe to write to application logs.

### Difference model

The result is represented by an ADT rather than string statuses:

```scala
sealed trait Difference

final case class MissingOnLeft(rightTime: Instant) extends Difference
final case class MissingOnRight(leftTime: Instant) extends Difference
final case class DuplicatesOnLeft(leftTime: Instant, count: Int) extends Difference
final case class DuplicatesOnRight(rightTime: Instant, count: Int) extends Difference
final case class Detailed(
  leftTime: Instant,
  rightTime: Instant,
  detail: DiffDetail
) extends Difference
```

For duplicate results, `count` is the number of additional entities after the first one in the key group.

## Example

```scala
import java.time.Instant

import net.cheltsov.diff.DiffEntity.SensitiveType.Per
import net.cheltsov.diff.DiffKeys.{EmptyKey, SingleKey}

final case class Customer(id: String, name: String, passportNumber: Option[String], updatedAt: Instant)

implicit val customerDiffable: Diffable[Customer, String, Nothing] =
  new Diffable[Customer, String, Nothing] {
    override def keys(value: Customer): DiffKeys[String, Nothing] =
      DiffKeys(SingleKey(value.id, "customerId"), EmptyKey)

    override def time(value: Customer): Instant =
      value.updatedAt

    override def sensitiveTypes: Map[String, DiffEntity.SensitiveType] =
      Map("passportNumber" -> Per)
  }

val legacy = List(Customer("42", "Alice", Some("AA123456"), Instant.now()))
val current = List(Customer("42", "Alicia", Some("BB987654"), Instant.now()))

val comparison =
  DiffLogProducer.single.logDiffs(legacy, current, DiffScope.Common)
```

The comparison produces one visible difference for `name` and one masked difference for `passportNumber`.

## Comparison semantics

### Matching

Entities are grouped by `DiffKeys`. For every matched key group, the first entity from each side is used for detailed field comparison. Additional entities are reported as duplicates.

### Nested products

Case classes and other non-empty `Product` values are compared recursively when both sides have the same field names.

```text
profile.address.city
```

If the product structures differ, the parent field is reported as a single difference.

### Options

Two `Some` values are compared by their contents. A `Some`/`None` mismatch is reported at the option field itself.

### Collections

Collections are compared as multisets:

- order is ignored;
- repeated values are counted;
- concrete collection type is not part of equality.

Therefore:

```scala
List(1, 2, 3) == Vector(3, 2, 1) // equal for this comparison model
List(1, 1, 2) != List(1, 2, 2)   // different multiplicities
```

A collection difference is reported as one field-level difference; the library does not traverse internal collection implementation details.

### Sensitive fields

Sensitive paths are configured in `Diffable.sensitiveTypes`. Parent and child paths overlap intentionally:

```scala
Map("profile.address" -> Per)
```

protects `profile.address.city`, while a configured leaf path also protects a difference reported at its parent level.

`DiffLogProducer` logs placeholders such as `hidden_per` instead of configured values.

## Metrics

The service records the following ZIO metrics:

| Metric | Meaning |
|---|---|
| `diff_entity_total` | Number of unique key groups seen on each side |
| `diff_entity_many_total` | Number of additional duplicate entities |
| `diff_entity_omitted_total` | Key groups missing from the opposite side |
| `diff_entity_same_total` | Matched key groups, labelled as equal or not equal |
| `entity_same_diff_fields_total` | Individual changed fields in matched entities |

Common labels include `diff_scope`, `system`, `equality`, and `field`.

## Tests

The test suite covers:

- equal entities;
- missing entities on both sides;
- main and additional key matching;
- duplicate groups;
- top-level and nested field differences;
- `Some` versus `None`;
- order-independent collection comparison across collection implementations;
- repeated collection elements;
- `Vector` comparison without traversing collection internals;
- exact, parent, and child sensitive-path matching.

Run it with:

```bash
sbt clean test
```

## Technology

- Scala 2.13
- ZIO 2
- ZIO Prelude
- Enumeratum
- ZIO Test

## Design trade-offs

- The library uses runtime `Product` inspection instead of macros or reflection-based schema derivation. This keeps integration small, but field renames are discovered only when comparisons run.
- Collection order and concrete collection type are intentionally ignored.
- Only the first entity in each duplicate key group is used for detailed comparison; the number of additional entities is reported separately.
- Difference keys are logged without masking and must contain log-safe values.
- Metrics are process-local ZIO metrics; exporting them depends on the host application's observability setup.
