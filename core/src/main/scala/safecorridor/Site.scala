package safecorridor

enum Message:
  /** A snapshot of the sender's contribution. */
  case Snap(snapshot: Snapshot)

  /** "I durably hold your snapshot up to this offset." */
  case Ack(offset: Offset)

final case class Envelope(from: SiteId, to: SiteId, message: Message)

/** Deliberate defects, enabled only to prove that the invariant checks can fail. */
enum Bug:
  /** Acknowledge a snapshot on receipt, before the view that holds it is durable. */
  case AckBeforeDurable

  /** Constrain dispatch by the latest published snapshot only, not by every snapshot a peer may hold. */
  case LastPublishedOnly

  /** Publish a lagging projection even when the position has already moved beyond budget of it. */
  case SkipPublishCheck

/** What survives a crash. */
final case class Durable(
    ledger: Ledger,
    checkpoint: Projection,
    outstanding: Outstanding,
    view: PortfolioView,
    activations: Map[Period, Activation],
    open: Set[Period]
)

/** This site's portfolio view of one period, exact for its own contribution, and the bound around it. */
final case class Estimate(period: Period, view: Wh, bound: Bound, verdict: Verdict)

/** One grid area: its durable state, plus a volatile projection and view that are rebuilt from their durable
  * checkpoints after a crash. Every transition is a pure function.
  */
final case class SiteState(
    id: SiteId,
    peers: Set[SiteId],
    durable: Durable,
    projection: Projection,
    view: PortfolioView,
    bugs: Set[Bug]
):
  def ledger: Ledger = durable.ledger
  def position(period: Period): Wh = ledger.position(period)
  def activations: Map[Period, Activation] = durable.activations
  def budgets(period: Period): Option[Budgets] = activations.get(period).map(_.budgets(id))

  /** Snapshots that bound how far this site may move without asking. */
  private def constraining: Iterable[Map[Period, Wh]] =
    val out = durable.outstanding
    if bugs(Bug.LastPublishedOnly) then List(out.published(out.latestOffset)) else out.outstanding(peers)

  def interval(period: Period): Option[(Wh, Wh)] = budgets(period).map(Escrow.interval(constraining, period, _))

  def limits(period: Period): Option[Limits] =
    for
      activation <- activations.get(period) if durable.open(period)
      (lo, hi) <- interval(period)
    yield Limits(lo, hi, activation.feeders(id))

  def activate(activation: Activation): SiteState =
    withDurable(
      durable.copy(
        activations = activations.updated(activation.period, activation),
        open = durable.open + activation.period
      )
    )

  def close(period: Period): SiteState = withDurable(durable.copy(open = durable.open - period))

  /** Dispatch: the ledger commits the fact only inside the escrow interval and below the feeder ceiling. */
  def propose(fact: Fact): (SiteState, CommitResult) =
    val (next, result) = ledger.commit(fact, limits)
    (withDurable(durable.copy(ledger = next)), result)

  /** Advances the volatile projection by up to `max` facts. */
  def project(max: Int): SiteState = copy(projection = projection.advance(ledger.log, max))

  /** Records the projection as a new snapshot when it is ahead of the last one and safe to publish, then
    * rebroadcasts the latest published snapshot. The snapshot is recorded durably before it is sent.
    */
  def publish: (SiteState, List[Envelope]) =
    val out = durable.outstanding
    val candidate = Snapshot.of(projection)
    val ownBudgets = activations.map((period, a) => period -> a.budgets(id))
    val safe = bugs(Bug.SkipPublishCheck) || Escrow.canPublish(candidate.totals, position, ownBudgets)
    val next =
      if candidate.offset > out.latestOffset && safe then out.publish(candidate.offset, candidate.totals) else out
    val latest = Snapshot(id, next.latestOffset, next.published(next.latestOffset))
    (withDurable(durable.copy(outstanding = next)), toPeers(Message.Snap(latest)))

  def receive(from: SiteId, message: Message): (SiteState, List[Envelope]) = message match
    case Message.Snap(snapshot) =>
      require(snapshot.site == from, s"$from sent a snapshot of ${snapshot.site}")
      val merged = view.merge(snapshot)
      // Acknowledge only what is durable; a duplicate or republished snapshot re-acknowledges it.
      val held = (if bugs(Bug.AckBeforeDurable) then merged else durable.view).offsetOf(from)
      val ack = Option.when(held > Offset.genesis)(Envelope(id, from, Message.Ack(held)))
      (copy(view = merged), ack.toList)
    case Message.Ack(offset) =>
      (withDurable(durable.copy(outstanding = durable.outstanding.ack(from, offset, peers))), Nil)

  /** The durable flush: checkpoints the projection and the view, then acknowledges the snapshots that are now
    * durable. Acknowledging earlier would let a crash roll this site back to a snapshot its peer has stopped
    * counting as outstanding.
    */
  def flush: (SiteState, List[Envelope]) =
    val advanced = peers.toList.sorted.filter(p => view.offsetOf(p) > durable.view.offsetOf(p))
    val acks = advanced.map(p => Envelope(id, p, Message.Ack(view.offsetOf(p))))
    (withDurable(durable.copy(checkpoint = projection, view = view)), acks)

  /** Loses everything volatile: the projection and the view restart from their durable checkpoints. */
  def crash: SiteState = copy(projection = durable.checkpoint, view = durable.view)

  def estimate(period: Period): Option[Estimate] =
    activations
      .get(period)
      .map: a =>
        val others = peers.toList
        val total = position(period) + Wh.sum(others.map(view.total(_, period)))
        val bound = Bound(
          total - Wh.sum(others.map(a.budgets(_).down)),
          total + Wh.sum(others.map(a.budgets(_).up))
        )
        Estimate(period, total, bound, Bound.verdict(bound, a.corridor))

  private def toPeers(message: Message): List[Envelope] = peers.toList.sorted.map(Envelope(id, _, message))
  private def withDurable(next: Durable): SiteState = copy(durable = next)

object SiteState:
  def initial(id: SiteId, peers: Set[SiteId], bugs: Set[Bug] = Set.empty): SiteState =
    val projection = Projection.empty(id)
    val durable =
      Durable(Ledger.empty(id), projection, Outstanding.initial, PortfolioView.empty, Map.empty, Set.empty)
    SiteState(id, peers, durable, projection, PortfolioView.empty, bugs)
