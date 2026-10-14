package safecorridor.sim

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.{Random, Supervisor}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.duration.*
import safecorridor.*

final case class Failure(at: FiniteDuration, violations: List[Violation])

final case class Outcome(config: Config, world: World, failure: Option[Failure], endViolations: List[Violation]):
  def passed: Boolean = failure.isEmpty && endViolations.isEmpty

object Simulation:
  /** Runs under TestControl: simulated time, and a scheduler seeded from the run seed, so that a seed replays
    * the same timings and the same fiber interleaving every time.
    */
  def run(config: Config): IO[Outcome] =
    val schedulerSeed = Base64.getEncoder.encodeToString(config.seed.toString.getBytes(UTF_8))
    TestControl.executeEmbed(program(config), seed = Some(schedulerSeed))

  private def program(config: Config): IO[Outcome] =
    Supervisor[IO](await = false).use: supervisor =>
      for
        world <- Ref.of[IO, World](World.initial(config))
        failure <- Deferred[IO, Failure]
        net <- config.ids.traverse(id => random(config, "net", id.name).tupleLeft(id)).map(_.toMap)
        engine = Engine(config, world, failure, supervisor, net)
        result <- engine.loops.surround(engine.timeline.race(failure.get))
        end <- world.get
        endViolations = if result.isLeft then Invariants.deep(end.states) ++ Invariants.convergence(end.states) else Nil
      yield Outcome(config, end, result.toOption, endViolations)

  private[sim] def random(config: Config, stream: String*): IO[Random[IO]] =
    Random.scalaUtilRandomSeedLong[IO](Seeds.derive(config.seed, stream*))

/** Drives the pure World from fibers that sleep on simulated time. Every change goes through `step`, which
  * applies it atomically and checks every invariant on the result, so "asserted after every step" is
  * literal.
  */
private final class Engine(
    config: Config,
    world: Ref[IO, World],
    failure: Deferred[IO, Failure],
    supervisor: Supervisor[IO],
    net: Map[SiteId, Random[IO]]
):
  def step[A](change: World => (World, A)): IO[A] =
    for
      now <- IO.monotonic
      (a, violations) <- world.modify: w =>
        val (next, a) = change(w)
        (next.trackSlack, (a, Invariants.check(next.states)))
      _ <- fail(now, violations)
    yield a

  private def fail(at: FiniteDuration, violations: List[Violation]): IO[Unit] =
    failure.complete(Failure(at, violations)).void.unlessA(violations.isEmpty)

  /** Activations, closes and faults in time order; then heal everything and let the system quiesce. */
  def timeline: IO[Unit] =
    val periods = (0 until config.periods).toList
    val events =
      periods.map(p => config.orderAt(p) -> Event.Open(p)) ++
        periods.map(p => config.endOf(p) -> Event.Close(p)) ++
        Faults.schedule(config).map(t => t.at -> Event.Inject(t.fault))
    for
      _ <- events.sortBy(_._1).traverse_ { (at, event) =>
        sleepUntil(at) >> step(w => (w.apply(event, at, config), ())) >> deepCheck.whenA(event.isInstanceOf[Event.Close])
      }
      _ <- step(w => (w.healAll, ()))
      _ <- IO.sleep(config.quiesce)
    yield ()

  private def sleepUntil(at: FiniteDuration): IO[Unit] = IO.monotonic.flatMap(now => IO.sleep(at - now).whenA(at > now))

  /** Recomputes every ledger and projection from its log. Too slow for every step, so it runs at each close. */
  private def deepCheck: IO[Unit] =
    (IO.monotonic, world.get).flatMapN((now, w) => fail(now, Invariants.deep(w.states)))

  def loops: Resource[IO, Unit] = config.ids.traverse_(siteLoops)

  private def siteLoops(site: SiteId): Resource[IO, Unit] =
    def rng(stream: String) = Resource.eval(Simulation.random(config, stream, site.name))
    for
      dispatchRng <- rng("dispatch")
      projectRng <- rng("project")
      flushRng <- rng("flush")
      publishRng <- rng("publish")
      _ <- every(config.dispatchEvery, dispatchRng)(dispatch(site, dispatchRng)).background
      _ <- every(config.projectEvery, projectRng)(step(w => (w.project(site, config.projectBatch), ()))).background
      _ <- every(config.flushEvery, flushRng)(step(_.flush(site)).flatMap(transmit(site, _))).background
      _ <- every(config.publishEvery, publishRng)(publish(site)).background
    yield ()

  private def every(interval: FiniteDuration, rng: Random[IO])(action: IO[Unit]): IO[Nothing] =
    (rng.betweenDouble(0.5, 1.5).flatMap(f => IO.sleep((interval.toNanos * f).toLong.nanos)) >> action).foreverM

  private def dispatch(site: SiteId, rng: Random[IO]): IO[Unit] =
    for
      draw <- (rng.nextIntBounded(1 << 20), rng.nextDouble, rng.nextDouble, rng.nextBoolean, rng.nextDouble, rng.nextDouble)
        .mapN(Draw.apply)
      pressured <- step(_.dispatch(site, draw, config))
      _ <- publish(site).whenA(pressured)
    yield ()

  private def publish(site: SiteId): IO[Unit] = step(_.publish(site)).flatMap(transmit(site, _))

  private def transmit(from: SiteId, envelopes: List[Envelope]): IO[Unit] = envelopes.traverse_(send(net(from)))

  private def send(rng: Random[IO])(envelope: Envelope): IO[Unit] =
    for
      lost <- rng.nextDouble.map(_ < config.loss)
      duplicated <- rng.nextDouble.map(_ < config.duplication)
      first <- latency(rng)
      second <- latency(rng)
      leaves <- step(_.send(envelope, lost))
      _ <- deliverAfter(first, envelope).whenA(leaves)
      _ <- deliverAfter(second, envelope).whenA(leaves && duplicated)
    yield ()

  private def deliverAfter(delay: FiniteDuration, envelope: Envelope): IO[Unit] =
    supervisor.supervise(IO.sleep(delay) >> step(_.receive(envelope)).flatMap(transmit(envelope.to, _))).void

  private def latency(rng: Random[IO]): IO[FiniteDuration] =
    rng.betweenLong(config.latency._1.toNanos, config.latency._2.toNanos).map(_.nanos)
