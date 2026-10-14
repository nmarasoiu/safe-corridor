package safecorridor.sim

import scala.concurrent.duration.FiniteDuration
import safecorridor.*

/** A site and how many overlapping outages currently hold it down. */
final case class Node(state: SiteState, crashes: Int):
  def up: Boolean = crashes == 0

/** An undirected link between two sites. */
final case class Link private (a: SiteId, b: SiteId)

object Link:
  def between(x: SiteId, y: SiteId): Link = if x.name <= y.name then new Link(x, y) else new Link(y, x)

/** Random draws for one dispatch decision, taken before the step so that the step itself stays pure. */
final case class Draw(pick: Int, noise: Double, size: Double, up: Boolean, dropout: Double, retry: Double)

enum Event:
  case Open(period: Int)
  case Close(period: Int)
  case Inject(fault: Fault)

final case class Stats(
    committed: Int = 0,
    retried: Int = 0,
    rejected: Map[String, Int] = Map.empty,
    published: Int = 0,
    sent: Int = 0,
    delivered: Int = 0,
    dropped: Int = 0,
    minSlack: Map[Period, Wh] = Map.empty,
    atClose: Map[Period, Map[SiteId, Estimate]] = Map.empty,
    faults: Vector[Timed] = Vector.empty
)

/** The whole simulated system. Every transition is pure; the engine applies each one atomically and checks
  * the invariants on the result.
  */
final case class World(
    nodes: Map[SiteId, Node],
    cuts: Map[Link, Int],
    dropouts: Map[(SiteId, Int), Int],
    nextFact: Map[SiteId, Long],
    lastFact: Map[SiteId, Fact],
    stats: Stats
):
  def states: List[SiteState] = nodes.values.map(_.state).toList
  def connected(a: SiteId, b: SiteId): Boolean = cuts.getOrElse(Link.between(a, b), 0) == 0

  private def counted(f: Stats => Stats): World = copy(stats = f(stats))

  private def onUp[A](site: SiteId, idle: A)(f: SiteState => (SiteState, A)): (World, A) =
    val node = nodes(site)
    if !node.up then (this, idle)
    else
      val (state, a) = f(node.state)
      (copy(nodes = nodes.updated(site, node.copy(state = state))), a)

  private def everywhere(f: SiteState => SiteState): World =
    copy(nodes = nodes.view.mapValues(n => n.copy(state = f(n.state))).toMap)

  def project(site: SiteId, max: Int): World = onUp(site, ())(s => (s.project(max), ()))._1

  def flush(site: SiteId): (World, List[Envelope]) = onUp(site, Nil)(_.flush)

  def publish(site: SiteId): (World, List[Envelope]) =
    def latest(w: World) = w.nodes(site).state.durable.outstanding.latestOffset
    val (next, sent) = onUp(site, Nil)(_.publish)
    (if latest(next) > latest(this) then next.counted(s => s.copy(published = s.published + 1)) else next, sent)

  /** Whether a message leaves its sender: the sender is up, the link is intact, and it is not lost. */
  def send(envelope: Envelope, lost: Boolean): (World, Boolean) =
    val leaves = nodes(envelope.from).up && connected(envelope.from, envelope.to) && !lost
    (counted(s => s.copy(sent = s.sent + 1, dropped = s.dropped + (if leaves then 0 else 1))), leaves)

  def receive(envelope: Envelope): (World, List[Envelope]) =
    if !nodes(envelope.to).up || !connected(envelope.from, envelope.to) then
      (counted(s => s.copy(dropped = s.dropped + 1)), Nil)
    else
      val (next, replies) = onUp(envelope.to, Nil)(_.receive(envelope.from, envelope.message))
      (next.counted(s => s.copy(delivered = s.delivered + 1)), replies)

  /** One dispatch decision: step toward this site's target for an open period, or, as noise, over- or
    * under-respond. A retry resubmits the previous fact unchanged. Returns whether the escrow interval pushed
    * back, which is the cue to publish at once.
    */
  def dispatch(site: SiteId, draw: Draw, config: Config): (World, Boolean) =
    val node = nodes(site)
    val open = node.state.durable.open.toList.sortBy(_.index)
    if !node.up || open.isEmpty then (this, false)
    else
      val period = open(draw.pick % open.size)
      val key = (site, period.index)
      val dropped = if draw.dropout < config.dropout then dropouts.updated(key, dropouts.getOrElse(key, 0) + 1) else dropouts
      val capability = (1.0 - 0.2 * dropped.getOrElse(key, 0)).max(0.0)
      val target = (config.target(site, period.index).value * capability).round
      val stride = (config.maxStep.value * draw.size).round.max(1L)
      val gap = target - node.state.position(period).value
      val delta =
        if draw.noise < config.noise then (if draw.up then stride else -stride)
        else gap.sign * gap.abs.min(stride)
      val retry = draw.retry < config.retry && lastFact.contains(site)
      if delta == 0 && !retry then (copy(dropouts = dropped), false)
      else
        val seq = nextFact.getOrElse(site, 0L)
        val fact =
          if retry then lastFact(site) else Fact.deviation(FactId(s"${site.name}-$seq"), site, period, Wh(delta))
        val (state, result) = node.state.propose(fact)
        val next = copy(
          nodes = nodes.updated(site, node.copy(state = state)),
          dropouts = dropped,
          nextFact = if retry then nextFact else nextFact.updated(site, seq + 1),
          lastFact = lastFact.updated(site, fact)
        )
        result match
          case CommitResult.Committed(_)        => (next.counted(s => s.copy(committed = s.committed + 1)), false)
          case CommitResult.AlreadyCommitted(_) => (next.counted(s => s.copy(retried = s.retried + 1)), false)
          case CommitResult.Rejected(reason) =>
            val kind = reason.productPrefix
            val pressured = reason match
              case _: Rejection.AboveInterval | _: Rejection.BelowInterval => true
              case _                                                       => false
            (next.counted(s => s.copy(rejected = s.rejected.updated(kind, s.rejected.getOrElse(kind, 0) + 1))), pressured)

  /** Activations and period closes come from the market interface, outside the corridor: they reach every
    * site's durable state, whether or not it is partitioned or down.
    */
  def apply(event: Event, at: FiniteDuration, config: Config): World = event match
    case Event.Open(p) => everywhere(_.activate(config.activation(p)))
    case Event.Close(p) =>
      val closed = everywhere(_.close(Period(p)))
      val estimates = closed.states.flatMap(s => s.estimate(Period(p)).map(s.id -> _)).toMap
      closed.counted(s => s.copy(atClose = s.atClose.updated(Period(p), estimates)))
    case Event.Inject(fault) =>
      inject(fault).counted(s => s.copy(faults = s.faults :+ Timed(at, fault)))

  private def inject(fault: Fault): World = fault match
    case Fault.Cut(a, b) =>
      val link = Link.between(a, b)
      copy(cuts = cuts.updated(link, cuts.getOrElse(link, 0) + 1))
    case Fault.Mend(a, b) =>
      val link = Link.between(a, b)
      val left = cuts.getOrElse(link, 0) - 1
      copy(cuts = if left > 0 then cuts.updated(link, left) else cuts - link)
    case Fault.Crash(site) =>
      val node = nodes(site)
      val crashed = if node.up then node.state.crash else node.state
      copy(nodes = nodes.updated(site, Node(crashed, node.crashes + 1)))
    case Fault.Restart(site) =>
      val node = nodes(site)
      copy(nodes = nodes.updated(site, node.copy(crashes = (node.crashes - 1).max(0))))

  def healAll: World = copy(cuts = Map.empty, nodes = nodes.view.mapValues(_.copy(crashes = 0)).toMap)

  /** The tightest margin seen so far between the truth and either edge of any site's bound. */
  def trackSlack: World =
    val all = states
    val slack = for
      r <- all
      period <- r.activations.keys
      e <- r.estimate(period)
      truth = Invariants.truth(all, period)
    yield period -> (truth - e.bound.lower).min(e.bound.upper - truth)
    val merged = slack.foldLeft(stats.minSlack): (acc, entry) =>
      acc.updated(entry._1, acc.get(entry._1).fold(entry._2)(_.min(entry._2)))
    counted(_.copy(minSlack = merged))

object World:
  def initial(config: Config): World =
    val ids = config.ids.toSet
    val nodes = config.ids.map(id => id -> Node(SiteState.initial(id, ids - id, config.bugs), crashes = 0))
    World(nodes.toMap, Map.empty, Map.empty, Map.empty, Map.empty, Stats())
