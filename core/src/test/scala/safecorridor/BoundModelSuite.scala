package safecorridor

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import org.scalacheck.rng.Seed

/** The bound as a theorem about the pure state machines, with no effects and no clock. Random interleavings of
  * dispatch, projection, publication, flushes, crashes and an adversarial network (reordering, loss,
  * duplication) are checked after every action.
  */
class BoundModelSuite extends munit.ScalaCheckSuite:
  import BoundModelSuite.*

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  property("the truth never leaves any site's reported two-sided bound"):
    forAll(genActions): actions =>
      assertEquals(Model.run(actions, Set.empty), None)

  property("after the network heals and quiesces, every view converges to the truth"):
    forAll(genActions): actions =>
      val model = actions.foldLeft(Model.initial(Set.empty))(_.apply(_))
      val settled = model.quiesce
      assertEquals(Invariants.convergence(settled.sites.values), Nil)
      assertEquals(Invariants.check(settled.sites.values) ++ Invariants.deep(settled.sites.values), Nil)

  for bug <- Bug.values do
    test(s"the checks catch the deliberate bug $bug"):
      val found = (0L until 3000L).iterator
        .map(i => genActions.pureApply(Gen.Parameters.default, Seed(i)))
        .flatMap(Model.run(_, Set(bug)))
        .nextOption()
      assert(found.isDefined, s"no violation found with $bug enabled")

object BoundModelSuite:
  val sites: List[SiteId] = List(SiteId("Oslo-N"), SiteId("Bergen-W"), SiteId("Trondheim-S"))
  val periods: List[Period] = List(Period(0), Period(1))

  def activation(p: Period): Activation =
    Activation(
      p,
      Corridor(Wh(100), Wh(300)),
      sites.map(_ -> Budgets(Wh(20), Wh(20))).toMap,
      sites.map(_ -> Wh(150)).toMap
    )

  enum Action:
    case Propose(site: Int, period: Int, delta: Long)
    case Project(site: Int, max: Int)
    case Publish(site: Int)
    case Flush(site: Int)
    case Deliver(index: Int)
    case Drop(index: Int)
    case Duplicate(index: Int)
    case Crash(site: Int)

  private val site = Gen.choose(0, sites.size - 1)
  private val index = Gen.choose(0, 1000)

  val genAction: Gen[Action] = Gen.frequency(
    30 -> (for s <- site; p <- Gen.choose(0, 1); d <- Gen.choose(-30L, 30L) yield Action.Propose(s, p, d)),
    10 -> (for s <- site; n <- Gen.choose(1, 3) yield Action.Project(s, n)),
    10 -> site.map(Action.Publish(_)),
    8 -> site.map(Action.Flush(_)),
    30 -> index.map(Action.Deliver(_)),
    3 -> index.map(Action.Drop(_)),
    3 -> index.map(Action.Duplicate(_)),
    4 -> site.map(Action.Crash(_))
  )

  val genActions: Gen[List[Action]] = Gen.listOfN(150, genAction)

  final case class Model(sites: Map[SiteId, SiteState], inFlight: Vector[Envelope], nextId: Int):
    private def site(i: Int): SiteId = BoundModelSuite.sites(i)
    private def update(id: SiteId, state: SiteState, sent: List[Envelope] = Nil): Model =
      copy(sites = sites.updated(id, state), inFlight = inFlight ++ sent)

    def apply(action: Action): Model = action match
      case Action.Propose(i, p, d) if d != 0 =>
        val fact = Fact.deviation(FactId(s"f$nextId"), site(i), Period(p), Wh(d))
        update(site(i), sites(site(i)).propose(fact)._1).copy(nextId = nextId + 1)
      case Action.Propose(_, _, _) => this
      case Action.Project(i, n)    => update(site(i), sites(site(i)).project(n))
      case Action.Publish(i) =>
        val (s, sent) = sites(site(i)).publish
        update(site(i), s, sent)
      case Action.Flush(i) =>
        val (s, sent) = sites(site(i)).flush
        update(site(i), s, sent)
      case Action.Crash(i) => update(site(i), sites(site(i)).crash)
      case _ if inFlight.isEmpty => this
      case Action.Deliver(k)   => deliver(k % inFlight.size)
      case Action.Drop(k)      => copy(inFlight = inFlight.patch(k % inFlight.size, Nil, 1))
      case Action.Duplicate(k) => copy(inFlight = inFlight :+ inFlight(k % inFlight.size))

    private def deliver(k: Int): Model =
      val env = inFlight(k)
      val (s, sent) = sites(env.to).receive(env.from, env.message)
      copy(inFlight = inFlight.patch(k, Nil, 1)).update(env.to, s, sent)

    def violations: List[Violation] = Invariants.check(sites.values) ++ Invariants.deep(sites.values)

    /** Heal: no more dispatch, every projection catches up, and messages flow in order until nothing changes. */
    def quiesce: Model =
      def drain(m: Model): Model = if m.inFlight.isEmpty then m else drain(m.deliver(0))
      def round(m: Model): Model =
        val caughtUp = BoundModelSuite.sites.foldLeft(m)((acc, id) => acc.update(id, acc.sites(id).project(Int.MaxValue)))
        val published = BoundModelSuite.sites.foldLeft(caughtUp): (acc, id) =>
          val (s, sent) = acc.sites(id).publish
          acc.update(id, s, sent)
        val flushed = BoundModelSuite.sites.foldLeft(drain(published)): (acc, id) =>
          val (s, sent) = acc.sites(id).flush
          acc.update(id, s, sent)
        drain(flushed)
      round(round(this))

  object Model:
    def initial(bugs: Set[Bug]): Model =
      val states = sites.map: id =>
        id -> periods.foldLeft(SiteState.initial(id, sites.toSet - id, bugs))((s, p) => s.activate(activation(p)))
      Model(states.toMap, Vector.empty, 0)

    /** The first violation, with the action that caused it. */
    def run(actions: List[Action], bugs: Set[Bug]): Option[(Action, List[Violation])] =
      actions
        .scanLeft((initial(bugs), Option.empty[Action]))((acc, a) => (acc._1.apply(a), Some(a)))
        .collectFirst { case (m, Some(a)) if m.violations.nonEmpty => (a, m.violations) }
