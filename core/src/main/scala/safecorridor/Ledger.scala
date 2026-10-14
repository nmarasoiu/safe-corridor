package safecorridor

/** Per-period limits the ledger enforces atomically at commit: the escrow interval `[lo, hi]` and the local
  * feeder ceiling.
  */
final case class Limits(lo: Wh, hi: Wh, feeder: Wh)

object Limits:
  /** No limits: a single site before escrow exists. */
  val unbounded: Limits = Limits(Wh(Long.MinValue / 4), Wh(Long.MaxValue / 4), Wh(Long.MaxValue / 4))

enum Rejection:
  case Conflict(existing: Offset)
  case PeriodClosed(period: Period)
  case BelowInterval(lo: Wh, attempted: Wh)
  case AboveInterval(hi: Wh, attempted: Wh)
  case FeederCeiling(feeder: Wh, attempted: Wh)

enum CommitResult:
  case Committed(offset: Offset)
  case AlreadyCommitted(offset: Offset)
  case Rejected(reason: Rejection)

/** One site's system of record: an append-only log of facts, deduplicated by fact ID. It decides alone, inside
  * its limits.
  */
final case class Ledger(area: SiteId, log: Vector[Fact], byId: Map[FactId, Offset], balances: Map[AccountId, Wh]):
  def offset: Offset = Offset(log.size.toLong)
  def balance(account: AccountId): Wh = balances.getOrElse(account, Wh.zero)
  def position(period: Period): Wh = balance(AccountId(AccountCode.Delivery, area, period))
  def factAt(offset: Offset): Fact = log((offset.value - 1).toInt)

  /** Commits `fact` if it fits the limits of its period; `None` means the period is not open. Resubmitting a
    * committed fact is a no-op that returns its original offset.
    */
  def commit(fact: Fact, limits: Period => Option[Limits]): (Ledger, CommitResult) =
    byId.get(fact.id) match
      case Some(at) if factAt(at) == fact => (this, CommitResult.AlreadyCommitted(at))
      case Some(at)                       => (this, CommitResult.Rejected(Rejection.Conflict(at)))
      case None =>
        require(fact.debit.area == area && fact.credit.area == area, s"fact ${fact.id} does not belong to $area")
        limits(fact.period) match
          case None => (this, CommitResult.Rejected(Rejection.PeriodClosed(fact.period)))
          case Some(l) =>
            val attempted = position(fact.period) + fact.deliveryDelta(area)
            if attempted > l.feeder then (this, CommitResult.Rejected(Rejection.FeederCeiling(l.feeder, attempted)))
            else if attempted > l.hi then (this, CommitResult.Rejected(Rejection.AboveInterval(l.hi, attempted)))
            else if attempted < l.lo then (this, CommitResult.Rejected(Rejection.BelowInterval(l.lo, attempted)))
            else
              val at = offset.next
              val next = copy(
                log = log :+ fact,
                byId = byId.updated(fact.id, at),
                balances = balances
                  .updated(fact.debit, balance(fact.debit) - fact.amount)
                  .updated(fact.credit, balance(fact.credit) + fact.amount)
              )
              (next, CommitResult.Committed(at))

object Ledger:
  def empty(area: SiteId): Ledger = Ledger(area, Vector.empty, Map.empty, Map.empty)

  /** Balances recomputed from the log alone, independent of the incremental bookkeeping. */
  def balancesOf(log: Iterable[Fact]): Map[AccountId, Wh] =
    log.foldLeft(Map.empty[AccountId, Wh]): (acc, f) =>
      acc
        .updated(f.debit, acc.getOrElse(f.debit, Wh.zero) - f.amount)
        .updated(f.credit, acc.getOrElse(f.credit, Wh.zero) + f.amount)
