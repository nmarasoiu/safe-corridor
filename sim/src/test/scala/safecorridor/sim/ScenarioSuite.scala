package safecorridor.sim

import munit.CatsEffectSuite
import scala.concurrent.duration.*
import safecorridor.*

class ScenarioSuite extends CatsEffectSuite:
  import Config.{bergen, oslo, trondheim}

  private val ids = List(oslo, bergen, trondheim)
  private def scripted(events: List[Timed]) = Config.default(1).copy(periods = 4, faults = FaultPlan.Scripted(events))

  private def assertPassed(outcome: Outcome): Unit =
    assert(outcome.passed, Report.render(outcome))
    assert(outcome.world.stats.committed > 0, "nothing was dispatched")

  test("no faults: the bound holds and every view converges"):
    Simulation.run(Config.default(1).copy(periods = 4, faults = FaultPlan.Quiet)).map(assertPassed)

  test("one site isolated mid-ramp, then healed"):
    Simulation.run(scripted(Faults.isolate(bergen, ids, 3.minutes, 13.minutes))).map(assertPassed)

  test("every link cut for fifteen minutes"):
    val all = Faults.cut(oslo, bergen, 5.minutes, 20.minutes) ++ Faults.cut(bergen, trondheim, 5.minutes, 20.minutes) ++
      Faults.cut(trondheim, oslo, 5.minutes, 20.minutes)
    Simulation.run(scripted(all)).map(assertPassed)

  test("repeated short crashes on every site, landing inside publish and flush windows"):
    val crashes =
      for
        (site, i) <- ids.zipWithIndex
        k <- 1 to 50
        from = (60L * k + 13L * i).seconds
      yield Faults.outage(site, from, from + (2L + k % 4).seconds)
    Simulation.run(scripted(crashes.flatten)).map(assertPassed)

  test("a feeder ceiling binds locally, without coordination, even when partitioned"):
    val tight = scripted(Faults.isolate(trondheim, ids, 2.minutes, 25.minutes))
    val config = tight.copy(sites = tight.sites.map(s => if s.id == trondheim then s.copy(feeder = Wh.kWh(250)) else s))
    Simulation
      .run(config)
      .map: outcome =>
        assertPassed(outcome)
        assert(outcome.world.stats.rejected.getOrElse("FeederCeiling", 0) > 0, "the ceiling never bound")
        (0 until config.periods).foreach: p =>
          assert(outcome.world.nodes(trondheim).state.position(Period(p)) <= Wh.kWh(250))

  test("a seed replays exactly"):
    val config = Config.default(7).copy(periods = 3)
    for
      first <- Simulation.run(config)
      second <- Simulation.run(config)
    yield assertEquals(Report.render(first), Report.render(second))
