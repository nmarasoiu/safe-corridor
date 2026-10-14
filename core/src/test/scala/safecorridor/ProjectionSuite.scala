package safecorridor

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class ProjectionSuite extends munit.ScalaCheckSuite:
  private val site = SiteId("Oslo-N")

  private val genLog: Gen[Vector[Fact]] =
    val deviation = for
      period <- Gen.choose(0, 2)
      delta <- Gen.oneOf(Gen.choose(-500L, -1L), Gen.choose(1L, 500L))
    yield (period, delta)
    Gen.listOf(deviation).map(_.zipWithIndex.toVector.map { case ((p, d), i) =>
      Fact.deviation(FactId(s"f$i"), site, Period(p), Wh(d))
    })

  private enum Op:
    case Step, Checkpoint, Crash

  private val genOps: Gen[List[Op]] = Gen.listOf(Gen.frequency(6 -> Op.Step, 2 -> Op.Checkpoint, 1 -> Op.Crash))

  private def exact(log: Vector[Fact], p: Projection): Boolean = p == Projection.of(site, log.take(p.offset.value.toInt))

  property("state and offset are committed together: a crash anywhere loses nothing and doubles nothing"):
    forAll(genLog, genOps): (log, ops) =>
      val (durable, volatile, ok) =
        ops.foldLeft((Projection.empty(site), Projection.empty(site), true)) { case ((durable, volatile, ok), op) =>
          val (d, v) = op match
            case Op.Step       => (durable, volatile.step(log).getOrElse(volatile))
            case Op.Checkpoint => (volatile, volatile)
            case Op.Crash      => (durable, durable)
          (d, v, ok && exact(log, d) && exact(log, v))
        }
      val finished = volatile.catchUp(log)
      Prop(ok && exact(log, durable) && finished == Projection.of(site, log) && finished.offset == Offset(log.size))

  property("positions do not depend on the order facts are applied in"):
    forAll(genLog, Gen.long): (log, seed) =>
      val shuffled = new scala.util.Random(seed).shuffle(log)
      Projection.of(site, shuffled).positions == Projection.of(site, log).positions

  property("the projection agrees with the ledger's own balances"):
    forAll(genLog): log =>
      val ledger = log.foldLeft(Ledger.empty(site))((l, f) => l.commit(f, _ => Some(Limits.unbounded))._1)
      val projection = Projection.of(site, ledger.log)
      (0 to 2).forall(i => projection.position(Period(i)) == ledger.position(Period(i)))
