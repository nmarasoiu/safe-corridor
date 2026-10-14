package safecorridor

class LedgerSuite extends munit.FunSuite:
  private val oslo = SiteId("Oslo-N")
  private val p0 = Period(0)
  private val open: Period => Option[Limits] = _ => Some(Limits.unbounded)

  private def dev(id: String, delta: Long) = Fact.deviation(FactId(id), oslo, p0, Wh(delta))

  test("a committed deviation moves the delivery position and keeps the ledger balanced"):
    val (ledger, result) = Ledger.empty(oslo).commit(dev("a", 500), open)
    assertEquals(result, CommitResult.Committed(Offset(1)))
    assertEquals(ledger.position(p0), Wh(500))
    assertEquals(ledger.balance(AccountId(AccountCode.AssetGroup, oslo, p0)), Wh(-500))
    assertEquals(Wh.sum(ledger.balances.values), Wh.zero)

  test("a negative deviation is the reverse transfer"):
    val (ledger, _) = Ledger.empty(oslo).commit(dev("a", -300), open)
    assertEquals(ledger.position(p0), Wh(-300))
    assertEquals(ledger.log.head.debit.code, AccountCode.Delivery)

  test("resubmitting a fact is idempotent"):
    val (once, _) = Ledger.empty(oslo).commit(dev("a", 500), open)
    val (twice, result) = once.commit(dev("a", 500), open)
    assertEquals(result, CommitResult.AlreadyCommitted(Offset(1)))
    assertEquals(twice, once)

  test("reusing a fact ID with different content is a conflict"):
    val (once, _) = Ledger.empty(oslo).commit(dev("a", 500), open)
    val (after, result) = once.commit(dev("a", 400), open)
    assertEquals(result, CommitResult.Rejected(Rejection.Conflict(Offset(1))))
    assertEquals(after, once)

  test("a period without limits is closed"):
    val (_, result) = Ledger.empty(oslo).commit(dev("a", 1), _ => None)
    assertEquals(result, CommitResult.Rejected(Rejection.PeriodClosed(p0)))

  test("limits are enforced at commit, the feeder ceiling first"):
    val limits: Period => Option[Limits] = _ => Some(Limits(Wh(-100), Wh(200), Wh(150)))
    val empty = Ledger.empty(oslo)
    assertEquals(empty.commit(dev("a", 160), limits)._2, CommitResult.Rejected(Rejection.FeederCeiling(Wh(150), Wh(160))))
    val wide: Period => Option[Limits] = _ => Some(Limits(Wh(-100), Wh(200), Wh(1000)))
    assertEquals(empty.commit(dev("a", 201), wide)._2, CommitResult.Rejected(Rejection.AboveInterval(Wh(200), Wh(201))))
    assertEquals(empty.commit(dev("a", -101), wide)._2, CommitResult.Rejected(Rejection.BelowInterval(Wh(-100), Wh(-101))))
    assertEquals(empty.commit(dev("a", 150), limits)._2, CommitResult.Committed(Offset(1)))

  test("a site only commits facts for its own area"):
    intercept[IllegalArgumentException]:
      Ledger.empty(oslo).commit(Fact.deviation(FactId("a"), SiteId("Bergen-W"), p0, Wh(1)), open)
