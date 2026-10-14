package safecorridor

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class SnapshotSuite extends munit.ScalaCheckSuite:
  private val sites = List(SiteId("Oslo-N"), SiteId("Bergen-W"), SiteId("Trondheim-S"))

  // A snapshot is determined by its site and offset, so generated totals are a function of both.
  private val genSnapshot: Gen[Snapshot] =
    for
      site <- Gen.oneOf(sites)
      offset <- Gen.choose(0L, 20L)
    yield Snapshot(site, Offset(offset), Map(Period(0) -> Wh(offset * 7 + site.name.length)))

  private val genView: Gen[PortfolioView] =
    Gen.listOf(genSnapshot).map(_.foldLeft(PortfolioView.empty)(_.merge(_)))

  property("merge is commutative"):
    forAll(genView, genView)((a, b) => a.mergeAll(b) == b.mergeAll(a))

  property("merge is idempotent"):
    forAll(genView, genSnapshot)((v, s) => v.mergeAll(v) == v && v.merge(s).merge(s) == v.merge(s))

  property("merge is associative"):
    forAll(genView, genView, genView)((a, b, c) => a.mergeAll(b).mergeAll(c) == a.mergeAll(b.mergeAll(c)))

  property("a view keeps the highest offset per site, whatever the delivery order"):
    forAll(Gen.listOf(genSnapshot), Gen.long): (snapshots, seed) =>
      val reordered = new scala.util.Random(seed).shuffle(snapshots)
      val view = reordered.foldLeft(PortfolioView.empty)(_.merge(_))
      sites.forall: s =>
        view.offsetOf(s) == snapshots.filter(_.site == s).map(_.offset).maxOption.getOrElse(Offset.genesis)
