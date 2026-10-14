package safecorridor.sim

import cats.effect.{ExitCode, IO, IOApp}

/** `sbt "sim/run --seed 42"`, optionally with `--periods N` and `--aggressive`. Exits non-zero on any
  * violation, so a seed is a reproducible bug report.
  */
object Main extends IOApp:
  def run(args: List[String]): IO[ExitCode] =
    def flag(name: String): Option[String] = args.sliding(2).collectFirst { case List(`name`, value) => value }
    val seed = flag("--seed").fold(42L)(_.toLong)
    val base = if args.contains("--aggressive") then Config.aggressive(seed) else Config.default(seed)
    val config = flag("--periods").fold(base)(n => base.copy(periods = n.toInt))
    Simulation
      .run(config)
      .flatMap(outcome => IO.println(Report.render(outcome)).as(if outcome.passed then ExitCode.Success else ExitCode.Error))
