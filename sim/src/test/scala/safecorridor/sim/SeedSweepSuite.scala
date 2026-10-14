package safecorridor.sim

import cats.effect.IO
import cats.effect.syntax.all.*
import munit.CatsEffectSuite
import scala.concurrent.duration.*

/** Random fault schedules, one per seed. Widen with `sbt -Dsim.seeds=2000 "sim/testOnly *SeedSweepSuite"`. */
class SeedSweepSuite extends CatsEffectSuite:
  override def munitIOTimeout: Duration = 30.minutes

  private val seeds = sys.props.get("sim.seeds").fold(100)(_.toInt)
  private val parallelism = Runtime.getRuntime.availableProcessors

  private def sweep(config: Long => Config): IO[Unit] =
    (1L to seeds.toLong).toList
      .parTraverseN(parallelism)(seed => Simulation.run(config(seed)))
      .map: outcomes =>
        val failed = outcomes.filterNot(_.passed)
        assert(failed.isEmpty, failed.map(o => s"seed ${o.config.seed}\n${Report.render(o)}").mkString("\n"))

  test(s"$seeds seeds: default network and faults"):
    sweep(Config.default)

  test(s"$seeds seeds: lossy network, frequent faults, lagging projection and flush"):
    sweep(Config.aggressive)
