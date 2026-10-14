package safecorridor

/** "Up to offset N, my cumulative contribution to each period is C." A snapshot is a fold of a log prefix, so
  * it is fully determined by its site and offset: no clock is needed to define it.
  */
final case class Snapshot(site: SiteId, offset: Offset, totals: Map[Period, Wh]):
  def total(period: Period): Wh = totals.getOrElse(period, Wh.zero)

object Snapshot:
  def genesis(site: SiteId): Snapshot = Snapshot(site, Offset.genesis, Map.empty)
  def of(projection: Projection): Snapshot = Snapshot(projection.area, projection.offset, projection.positions)

/** The highest-offset snapshot held per site. Merging is a max-register per site, so duplicates and
  * reordering change nothing. Every site implicitly holds every other site's genesis snapshot.
  */
final case class PortfolioView(held: Map[SiteId, Snapshot]):
  def snapshotOf(site: SiteId): Snapshot = held.getOrElse(site, Snapshot.genesis(site))
  def offsetOf(site: SiteId): Offset = snapshotOf(site).offset
  def total(site: SiteId, period: Period): Wh = snapshotOf(site).total(period)

  def merge(snapshot: Snapshot): PortfolioView =
    if snapshot.offset > offsetOf(snapshot.site) then PortfolioView(held.updated(snapshot.site, snapshot))
    else this

  def mergeAll(other: PortfolioView): PortfolioView = other.held.values.foldLeft(this)(_.merge(_))

object PortfolioView:
  val empty: PortfolioView = PortfolioView(Map.empty)
