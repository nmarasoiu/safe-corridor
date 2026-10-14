package safecorridor

import scala.collection.immutable.SortedMap

/** How far a site may move its contribution below (`down`) and above (`up`) any snapshot a peer may hold,
  * without asking.
  */
final case class Budgets(down: Wh, up: Wh):
  require(down >= Wh.zero && up >= Wh.zero, s"negative budget: $this")
  def width: Wh = down + up

/** The snapshots this site has published, and the highest offset each peer has acknowledged holding durably.
  *
  * A peer may hold any snapshot published at or after the offset it last acknowledged, not necessarily the
  * latest one. So the snapshots that constrain this site are all those at or above the lowest acknowledged
  * offset. Genesis (offset 0, all zero) counts as published from the start.
  */
final case class Outstanding(published: SortedMap[Offset, Map[Period, Wh]], acked: Map[SiteId, Offset]):
  def latestOffset: Offset = published.lastKey

  def lowWatermark(peers: Set[SiteId]): Offset =
    peers.iterator.map(acked.getOrElse(_, Offset.genesis)).minOption.getOrElse(latestOffset)

  def outstanding(peers: Set[SiteId]): Iterable[Map[Period, Wh]] = published.rangeFrom(lowWatermark(peers)).values

  def publish(offset: Offset, totals: Map[Period, Wh]): Outstanding =
    require(offset > latestOffset, s"snapshot offsets must increase: $offset after $latestOffset")
    copy(published = published.updated(offset, totals))

  /** Records a peer's acknowledgement (a max-register) and forgets snapshots no peer can still hold. */
  def ack(peer: SiteId, offset: Offset, peers: Set[SiteId]): Outstanding =
    if offset <= acked.getOrElse(peer, Offset.genesis) then this
    else
      val next = copy(acked = acked.updated(peer, offset))
      next.copy(published = published.rangeFrom(next.lowWatermark(peers)))

object Outstanding:
  val initial: Outstanding = Outstanding(SortedMap(Offset.genesis -> Map.empty), Map.empty)

object Escrow:
  /** The local interval for `period`: the current position must stay within budget of every snapshot a peer
    * may hold. Never empty while the position is inside it.
    */
  def interval(outstanding: Iterable[Map[Period, Wh]], period: Period, budgets: Budgets): (Wh, Wh) =
    val seen = outstanding.map(_.getOrElse(period, Wh.zero))
    (seen.max - budgets.down, seen.min + budgets.up)

  /** A snapshot may be published only if the current position is already within budget of it. The projection
    * can lag the ledger, and publishing a snapshot the position has outrun would break the bound
    * retroactively.
    */
  def canPublish(totals: Map[Period, Wh], current: Period => Wh, budgets: Map[Period, Budgets]): Boolean =
    budgets.forall: (period, b) =>
      val drift = current(period) - totals.getOrElse(period, Wh.zero)
      drift >= -b.down && drift <= b.up
