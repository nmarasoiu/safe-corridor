package safecorridor.sim

import scala.concurrent.duration.FiniteDuration
import safecorridor.*

object Report:
  def render(outcome: Outcome): String =
    val c = outcome.config
    val w = outcome.world
    val ids = c.ids
    val out = new StringBuilder
    def line(s: String = ""): Unit = out.append(s).append('\n')

    line(s"The Safe Corridor: seed ${c.seed}, ${ids.size} sites, ${c.periods} periods of ${c.periodLength.toMinutes} min")
    line(s"Commitment ${c.commitment.show} kWh per period, corridor [${c.corridor.floor.show}, ${c.corridor.ceiling.show}] kWh")
    line()
    line(f"${"Site"}%-13s ${"down / up budget"}%22s ${"feeder ceiling"}%15s   (kWh)")
    c.sites.foreach: s =>
      line(f"${s.id.name}%-13s ${s.budgets.down.show}%10s / ${s.budgets.up.show}%-9s ${s.feeder.show}%15s")
    line()

    line("Faults")
    if w.stats.faults.isEmpty then line("  none")
    w.stats.faults.foreach(t => line(s"  ${clock(t.at)}  ${describe(t.fault)}"))
    if outcome.failure.isEmpty then line(s"  ${clock(c.horizon)}  heal everything, quiesce for ${c.quiesce.toMinutes} min")
    line()

    val siteColumns = ids.map(id => f"${id.name}%12s").mkString
    line(f"${"Period"}%-7s ${"Truth"}%10s$siteColumns ${"Min slack"}%10s   Provable at close (floor, ceiling)")
    (0 until c.periods).foreach: p =>
      val period = Period(p)
      if w.states.exists(_.activations.contains(period)) then
        val positions = ids.map(id => f"${w.nodes(id).state.position(period).show}%12s").mkString
        val slack = w.stats.minSlack.get(period).fold("-")(_.show)
        val verdicts = w.stats.atClose.get(period).fold("-"): estimates =>
          ids.map(id => estimates.get(id).fold("??")(e => mark(e.verdict.floorSafe) + mark(e.verdict.ceilingSafe))).mkString(" ")
        line(f"P$p%-6d ${Invariants.truth(w.states, period).show}%10s$positions $slack%10s   $verdicts")
    line("Energies in kWh. Slack is the tightest margin between the truth and either edge of any site's bound.")
    line("Marks per site, in column order: ✓ provably inside the corridor on that side, · not provable.")
    line()

    val s = w.stats
    val rejected =
      if s.rejected.isEmpty then "none" else s.rejected.toList.sorted.map((k, n) => s"$k $n").mkString(", ")
    line(s"Facts committed ${s.committed} (${s.retried} retries deduplicated); rejected: $rejected")
    line(s"Snapshots published ${s.published}; messages sent ${s.sent}, delivered ${s.delivered}, dropped ${s.dropped}")
    line()

    outcome.failure match
      case Some(f) =>
        line(s"FAIL at ${clock(f.at)}:")
        f.violations.foreach(v => line(s"  $v"))
      case None if outcome.endViolations.nonEmpty =>
        line("FAIL after quiescing:")
        outcome.endViolations.foreach(v => line(s"  $v"))
      case None =>
        line("PASS: the bound held after every step, no feeder ceiling was breached, every ledger balanced,")
        line("every projection was exact, and every view converged to the truth after healing.")
    out.toString

  private def mark(safe: Boolean): String = if safe then "✓" else "·"

  private def clock(d: FiniteDuration): String =
    val s = d.toSeconds
    f"${s / 3600}%d:${s / 60 % 60}%02d:${s % 60}%02d"

  private def describe(fault: Fault): String = fault match
    case Fault.Cut(a, b)     => s"cut     ${a.name} | ${b.name}"
    case Fault.Mend(a, b)    => s"mend    ${a.name} | ${b.name}"
    case Fault.Crash(site)   => s"crash   ${site.name}"
    case Fault.Restart(site) => s"restart ${site.name}"
