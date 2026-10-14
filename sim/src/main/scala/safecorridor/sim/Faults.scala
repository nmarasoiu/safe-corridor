package safecorridor.sim

import scala.concurrent.duration.*
import safecorridor.*

enum Fault:
  case Cut(a: SiteId, b: SiteId)
  case Mend(a: SiteId, b: SiteId)
  case Crash(site: SiteId)
  case Restart(site: SiteId)

final case class Timed(at: FiniteDuration, fault: Fault)

enum FaultPlan:
  case Quiet
  case Random(partitions: Int, crashes: Int)
  case Scripted(events: List[Timed])

object Faults:
  /** Cuts `site` off from every other site between `from` and `until`. */
  def isolate(site: SiteId, all: List[SiteId], from: FiniteDuration, until: FiniteDuration): List[Timed] =
    all.filterNot(_ == site).flatMap(other => cut(site, other, from, until))

  def cut(a: SiteId, b: SiteId, from: FiniteDuration, until: FiniteDuration): List[Timed] =
    List(Timed(from, Fault.Cut(a, b)), Timed(until, Fault.Mend(a, b)))

  def outage(site: SiteId, from: FiniteDuration, until: FiniteDuration): List[Timed] =
    List(Timed(from, Fault.Crash(site)), Timed(until, Fault.Restart(site)))

  def schedule(config: Config): List[Timed] = config.faults match
    case FaultPlan.Quiet            => Nil
    case FaultPlan.Scripted(events) => events.sortBy(_.at)
    case FaultPlan.Random(partitions, crashes) =>
      val rng = new scala.util.Random(Seeds.derive(config.seed, "faults"))
      val ids = config.ids
      val horizon = config.horizon.toMillis
      def window(shortest: FiniteDuration, longest: FiniteDuration) =
        val start = rng.between(0L, horizon)
        (start.millis, (start + rng.between(shortest.toMillis, longest.toMillis)).min(horizon).millis)
      val splits = List.fill(partitions):
        val (from, until) = window(30.seconds, 10.minutes)
        if rng.nextBoolean() then isolate(ids(rng.nextInt(ids.size)), ids, from, until)
        else
          val pair = rng.shuffle(ids)
          cut(pair(0), pair(1), from, until)
      val outages = List.fill(crashes):
        val (from, until) = window(5.seconds, 3.minutes)
        outage(ids(rng.nextInt(ids.size)), from, until)
      (splits ++ outages).flatten.sortBy(_.at)
