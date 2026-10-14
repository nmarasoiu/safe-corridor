package safecorridor

import scala.collection.immutable.SortedMap

class EscrowSuite extends munit.FunSuite:
  private val p0 = Period(0)
  private val (a, b) = (SiteId("a"), SiteId("b"))

  test("README §4 worked example: the floor is not provably safe until down-budget is recalled"):
    val corridor = Corridor(Wh.kWh(1250), Wh.kWh(1375))
    val budgets = List(Budgets(Wh.kWh(20), Wh.kWh(25)), Budgets(Wh.kWh(25), Wh.kWh(20)), Budgets(Wh.kWh(15), Wh.kWh(15)))
    val view = Wh.kWh(520 + 410 + 365)
    def around(bs: List[Budgets]) = Bound(view - Wh.sum(bs.map(_.down)), view + Wh.sum(bs.map(_.up)))

    assertEquals(around(budgets), Bound(Wh.kWh(1235), Wh.kWh(1355)))
    assertEquals(Bound.verdict(around(budgets), corridor), Verdict(floorSafe = false, ceilingSafe = true))
    assert(Bound.feasible(corridor, budgets))

    val recalled = budgets.updated(2, Budgets(Wh.zero, Wh.kWh(15)))
    assertEquals(around(recalled).lower, Wh.kWh(1250))
    assertEquals(Bound.verdict(around(recalled), corridor), Verdict(floorSafe = true, ceilingSafe = true))

  test("budgets that overcommit the corridor cannot be activated"):
    intercept[IllegalArgumentException]:
      Activation(p0, Corridor(Wh(0), Wh(100)), Map(a -> Budgets(Wh(30), Wh(30)), b -> Budgets(Wh(30), Wh(30))), Map.empty)

  test("the interval keeps the position within budget of every outstanding snapshot"):
    val outstanding = List(Map(p0 -> Wh(0)), Map(p0 -> Wh(20)))
    assertEquals(Escrow.interval(outstanding, p0, Budgets(Wh(10), Wh(15))), (Wh(10), Wh(15)))

  test("acknowledgements are a max-register, and prune only below the lowest one"):
    val published = Outstanding(SortedMap(Offset(0) -> Map.empty, Offset(3) -> Map.empty, Offset(5) -> Map.empty), Map.empty)
    val peers = Set(a, b)
    val afterA = published.ack(a, Offset(5), peers)
    assertEquals(afterA.published.keySet, Set(Offset(0), Offset(3), Offset(5)))
    val afterB = afterA.ack(b, Offset(3), peers)
    assertEquals(afterB.published.keySet, Set(Offset(3), Offset(5)))
    assertEquals(afterB.ack(b, Offset(0), peers), afterB)

  test("a lagging snapshot is publishable only if the position is still within budget of it"):
    val budgets = Map(p0 -> Budgets(Wh(10), Wh(15)))
    assert(Escrow.canPublish(Map(p0 -> Wh(100)), _ => Wh(115), budgets))
    assert(!Escrow.canPublish(Map(p0 -> Wh(100)), _ => Wh(116), budgets))
    assert(Escrow.canPublish(Map(p0 -> Wh(100)), _ => Wh(90), budgets))
    assert(!Escrow.canPublish(Map(p0 -> Wh(100)), _ => Wh(89), budgets))
