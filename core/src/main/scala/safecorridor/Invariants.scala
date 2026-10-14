package safecorridor

enum Violation:
  case BoundBreached(receiver: SiteId, period: Period, bound: Bound, truth: Wh)
  case VerdictUnsound(receiver: SiteId, period: Period, verdict: Verdict, truth: Wh)
  case Overcommitted(period: Period)
  case FeederBreached(site: SiteId, period: Period, position: Wh, feeder: Wh)
  case OutsideInterval(site: SiteId, period: Period, position: Wh, lo: Wh, hi: Wh)
  case Unbalanced(site: SiteId, net: Wh)
  case LedgerDiverged(site: SiteId)
  case ProjectionDiverged(site: SiteId, offset: Offset)
  case NotConverged(receiver: SiteId, site: SiteId, held: Offset, actual: Offset)

/** The omniscient observer. It sees every site's ledger at once, which no site can, and checks the claims of
  * README §7 against that ground truth.
  */
object Invariants:
  import Violation.*

  /** In this milestone the truth is the sum of all committed facts across sites. */
  def truth(sites: Iterable[SiteState], period: Period): Wh = Wh.sum(sites.iterator.map(_.position(period)))

  /** Cheap checks, run after every step. */
  def check(sites: Iterable[SiteState]): List[Violation] =
    val all = sites.toList
    val activations = all.flatMap(_.activations.values).distinctBy(_.period)
    val truths = activations.map(a => a.period -> truth(all, a.period)).toMap

    val bounds =
      for
        r <- all
        a <- r.activations.values.toList
        e <- r.estimate(a.period).toList
        t = truths(a.period)
        v <- List(
          Option.unless(e.bound.contains(t))(BoundBreached(r.id, a.period, e.bound, t)),
          Option.when(e.verdict.floorSafe && t < a.corridor.floor || e.verdict.ceilingSafe && t > a.corridor.ceiling)(
            VerdictUnsound(r.id, a.period, e.verdict, t)
          )
        ).flatten
      yield v

    val local =
      for
        s <- all
        a <- s.activations.values.toList
        pos = s.position(a.period)
        v <- List(
          Option.when(pos > a.feeders(s.id))(FeederBreached(s.id, a.period, pos, a.feeders(s.id))),
          s.interval(a.period).collect:
            case (lo, hi) if pos < lo || pos > hi => OutsideInterval(s.id, a.period, pos, lo, hi)
        ).flatten
      yield v

    val overcommitted =
      activations.filterNot(a => Bound.feasible(a.corridor, a.budgets.values)).map(a => Overcommitted(a.period))

    val unbalanced = all.flatMap: s =>
      val net = Wh.sum(s.ledger.balances.values)
      Option.when(net != Wh.zero)(Unbalanced(s.id, net))

    bounds ++ local ++ overcommitted ++ unbalanced

  /** Recomputes ledgers and projections from the logs alone. Slower; run at checkpoints in a simulation. */
  def deep(sites: Iterable[SiteState]): List[Violation] =
    sites.toList.flatMap: s =>
      def diverged(p: Projection) = Projection.of(s.id, s.ledger.log.take(p.offset.value.toInt)) != p
      List(
        Option.when(Ledger.balancesOf(s.ledger.log) != s.ledger.balances)(LedgerDiverged(s.id)),
        Option.when(diverged(s.durable.checkpoint))(ProjectionDiverged(s.id, s.durable.checkpoint.offset)),
        Option.when(diverged(s.projection))(ProjectionDiverged(s.id, s.projection.offset))
      ).flatten

  /** After healing and quiescing, every view holds every peer's full log. */
  def convergence(sites: Iterable[SiteState]): List[Violation] =
    val all = sites.toList
    for
      r <- all
      s <- all if s.id != r.id
      held = r.view.offsetOf(s.id) if held != s.ledger.offset
    yield NotConverged(r.id, s.id, held, s.ledger.offset)
