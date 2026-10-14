package safecorridor

enum AccountCode:
  case AssetGroup, Delivery

/** Accounts are keyed per period: energy at 14:00 is a different commodity from energy at 14:15. */
final case class AccountId(code: AccountCode, area: SiteId, period: Period)

/** A committed fact: one balanced double-entry transfer, in watt-hours. Facts are never edited; corrections
  * are new facts.
  */
final case class Fact(id: FactId, period: Period, debit: AccountId, credit: AccountId, amount: Wh):
  require(amount > Wh.zero, s"fact $id: amount must be positive, was $amount")
  require(debit != credit, s"fact $id: debit and credit must differ")
  require(debit.period == period && credit.period == period, s"fact $id: accounts must belong to its period")

  /** The fact's effect on `area`'s delivery position: positive when delivery is credited. */
  def deliveryDelta(area: SiteId): Wh =
    val delivery = AccountId(AccountCode.Delivery, area, period)
    (if credit == delivery then amount else Wh.zero) - (if debit == delivery then amount else Wh.zero)

object Fact:
  /** A signed deviation from baseline at `area`, as a transfer between its asset group and its delivery
    * account. Delivering more debits the asset group; delivering less is the reverse transfer.
    */
  def deviation(id: FactId, area: SiteId, period: Period, delta: Wh): Fact =
    require(delta != Wh.zero, s"fact $id: zero deviation")
    val group = AccountId(AccountCode.AssetGroup, area, period)
    val delivery = AccountId(AccountCode.Delivery, area, period)
    if delta > Wh.zero then Fact(id, period, group, delivery, delta)
    else Fact(id, period, delivery, group, -delta)
