package safecorridor

/** The safe corridor for one quantity: deliver at least `floor`, at most `ceiling`. */
final case class Corridor(floor: Wh, ceiling: Wh):
  require(floor <= ceiling, s"empty corridor: $this")
  def width: Wh = ceiling - floor

/** Where the truth is guaranteed to lie. */
final case class Bound(lower: Wh, upper: Wh):
  def contains(x: Wh): Boolean = lower <= x && x <= upper

/** One component per side of the corridor: nothing here is unconditionally safe. */
final case class Verdict(floorSafe: Boolean, ceilingSafe: Boolean)

object Bound:
  def verdict(bound: Bound, corridor: Corridor): Verdict =
    Verdict(bound.lower >= corridor.floor, bound.upper <= corridor.ceiling)

  /** `Σ(D + U) ≤ C − F`: the autonomy handed out never exceeds the width of the corridor. */
  def feasible(corridor: Corridor, budgets: Iterable[Budgets]): Boolean =
    Wh.sum(budgets.iterator.map(_.width)) <= corridor.width

/** An activation order for one period, the corridor it implies, and how that corridor is split. Bidding and
  * pricing are out of scope: activations arrive as facts.
  */
final case class Activation(
    period: Period,
    corridor: Corridor,
    budgets: Map[SiteId, Budgets],
    feeders: Map[SiteId, Wh]
):
  require(Bound.feasible(corridor, budgets.values), s"budgets overcommit the corridor of period $period")
