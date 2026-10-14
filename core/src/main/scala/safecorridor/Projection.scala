package safecorridor

/** Derived delivery position per period for one area, together with the log offset it reflects. It is
  * checkpointed as one value, so state and offset are committed in one atomic write and can never disagree.
  */
final case class Projection(area: SiteId, positions: Map[Period, Wh], offset: Offset):
  def position(period: Period): Wh = positions.getOrElse(period, Wh.zero)

  def applyFact(fact: Fact): Projection =
    Projection(area, positions.updated(fact.period, position(fact.period) + fact.deliveryDelta(area)), offset.next)

  /** Applies the next fact from `log`, if the projection is behind it. */
  def step(log: Vector[Fact]): Option[Projection] =
    Option.when(offset.value < log.size)(applyFact(log(offset.value.toInt)))

  /** Applies up to `max` facts from `log`. */
  def advance(log: Vector[Fact], max: Int): Projection =
    log.iterator.drop(offset.value.toInt).take(max).foldLeft(this)(_.applyFact(_))

  def catchUp(log: Vector[Fact]): Projection = advance(log, Int.MaxValue)

object Projection:
  def empty(area: SiteId): Projection = Projection(area, Map.empty, Offset.genesis)
  def of(area: SiteId, facts: Iterable[Fact]): Projection = facts.foldLeft(empty(area))(_.applyFact(_))
