package safecorridor.sim

import scala.concurrent.duration.*
import scala.util.hashing.MurmurHash3
import safecorridor.*

final case class SiteSpec(id: SiteId, budgets: Budgets, feeder: Wh, share: Double)

/** Everything a run depends on. The same config, seed included, always produces the same run. */
final case class Config(
    seed: Long,
    sites: List[SiteSpec],
    periods: Int,
    commitment: Wh,
    tolerance: Wh,
    periodLength: FiniteDuration,
    orderLead: FiniteDuration,
    dispatchEvery: FiniteDuration,
    maxStep: Wh,
    noise: Double,
    retry: Double,
    dropout: Double,
    jitter: (Double, Double),
    projectEvery: FiniteDuration,
    projectBatch: Int,
    flushEvery: FiniteDuration,
    publishEvery: FiniteDuration,
    latency: (FiniteDuration, FiniteDuration),
    loss: Double,
    duplication: Double,
    faults: FaultPlan,
    quiesce: FiniteDuration,
    bugs: Set[Bug]
):
  def ids: List[SiteId] = sites.map(_.id)
  def spec(site: SiteId): SiteSpec = sites.find(_.id == site).get

  /** Over-delivery beyond the tolerance band is treated as a hard ceiling (README §8). */
  def corridor: Corridor = Corridor(commitment, commitment + tolerance)

  /** The activation order arrives `orderLead` before the period starts; dispatch stays open until it ends. */
  def orderAt(p: Int): FiniteDuration = periodLength * p.toLong
  def endOf(p: Int): FiniteDuration = orderAt(p) + orderLead + periodLength
  def horizon: FiniteDuration = endOf(periods - 1)

  def activation(p: Int): Activation =
    Activation(Period(p), corridor, sites.map(s => s.id -> s.budgets).toMap, sites.map(s => s.id -> s.feeder).toMap)

  /** What `site` aims to deliver in period `p`: its share of the corridor's midpoint, jittered per period, so
    * that feeder ceilings sometimes bind and the floor is sometimes missed.
    */
  def target(site: SiteId, p: Int): Wh =
    val midpoint = commitment.value + tolerance.value / 2
    val rng = new scala.util.Random(Seeds.derive(seed, "target", site.name, p.toString))
    val factor = jitter._1 + rng.nextDouble() * (jitter._2 - jitter._1)
    Wh((midpoint * spec(site).share * factor).round)

object Config:
  val oslo: SiteId = SiteId("Oslo-N")
  val bergen: SiteId = SiteId("Bergen-W")
  val trondheim: SiteId = SiteId("Trondheim-S")

  /** README §4's worked example: 5 MW for 15 minutes, a +10% tolerance band, and its budgets and feeders. */
  def default(seed: Long): Config = Config(
    seed = seed,
    sites = List(
      SiteSpec(oslo, Budgets(Wh.kWh(20), Wh.kWh(25)), Wh.kWh(600), share = 0.40),
      SiteSpec(bergen, Budgets(Wh.kWh(25), Wh.kWh(20)), Wh.kWh(480), share = 0.33),
      SiteSpec(trondheim, Budgets(Wh.kWh(15), Wh.kWh(15)), Wh.kWh(400), share = 0.27)
    ),
    periods = 8,
    commitment = Wh.kWh(1250),
    tolerance = Wh.kWh(125),
    periodLength = 15.minutes,
    orderLead = 450.seconds,
    dispatchEvery = 4.seconds,
    maxStep = Wh.kWh(8),
    noise = 0.15,
    retry = 0.03,
    dropout = 0.0005,
    jitter = (0.85, 1.25),
    projectEvery = 2.seconds,
    projectBatch = 20,
    flushEvery = 2.seconds,
    publishEvery = 30.seconds,
    latency = (50.millis, 2.seconds),
    loss = 0.01,
    duplication = 0.01,
    faults = FaultPlan.Random(partitions = 4, crashes = 3),
    quiesce = 5.minutes,
    bugs = Set.empty
  )

  /** Harsher: more faults, a lossier network, and a projection and flush that lag, which widens every window a
    * protocol bug could slip through.
    */
  def aggressive(seed: Long): Config = default(seed).copy(
    periods = 4,
    projectEvery = 15.seconds,
    projectBatch = 3,
    flushEvery = 10.seconds,
    latency = (50.millis, 8.seconds),
    loss = 0.05,
    duplication = 0.05,
    faults = FaultPlan.Random(partitions = 8, crashes = 12)
  )

object Seeds:
  /** A stable seed for one named stream of randomness, so that streams never share draws. */
  def derive(seed: Long, parts: String*): Long =
    val key = parts.mkString("/")
    (MurmurHash3.stringHash(key, (seed >>> 32).toInt).toLong << 32) |
      (MurmurHash3.stringHash(key, seed.toInt).toLong & 0xffffffffL)
