package safecorridor.sim

import cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import safecorridor.*

/** The checker of the checker: each deliberate protocol bug must be caught by some seed. A sweep that cannot
  * fail proves nothing.
  */
class BugInjectionSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 10.minutes

  private val budget = 200L

  private def firstFailing(bug: Bug, seed: Long = 1): IO[Option[Outcome]] =
    if seed > budget then IO.pure(None)
    else
      Simulation
        .run(Config.aggressive(seed).copy(bugs = Set(bug)))
        .flatMap(o => if o.passed then firstFailing(bug, seed + 1) else IO.pure(Some(o)))

  for bug <- Bug.values do
    test(s"the simulation catches $bug"):
      firstFailing(bug).map: found =>
        assert(found.isDefined, s"no seed up to $budget exposed $bug")
        found.foreach(o => println(s"$bug exposed by seed ${o.config.seed}: ${o.failure.map(_.violations.head)}"))
