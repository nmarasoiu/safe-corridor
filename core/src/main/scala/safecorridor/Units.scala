package safecorridor

/** Energy in integer watt-hours. No quantity in the ledger is ever a floating-point number. */
opaque type Wh = Long

object Wh:
  val zero: Wh = 0L
  def apply(value: Long): Wh = value
  def kWh(value: Long): Wh = value * 1000L
  def sum(values: IterableOnce[Wh]): Wh = values.iterator.foldLeft(zero)(_ + _)

  given Ordering[Wh] = Ordering.Long

  extension (a: Wh)
    def value: Long = a
    def +(b: Wh): Wh = a + b
    def -(b: Wh): Wh = a - b
    def unary_- : Wh = -a
    def <(b: Wh): Boolean = a < b
    def <=(b: Wh): Boolean = a <= b
    def >(b: Wh): Boolean = a > b
    def >=(b: Wh): Boolean = a >= b
    def max(b: Wh): Wh = math.max(a, b)
    def min(b: Wh): Wh = math.min(a, b)
    def abs: Wh = math.abs(a)
    def show: String = f"${a / 1000.0}%.3f"

/** Position in one site's log. Offset N means "the first N facts"; offset 0 is the empty log. */
opaque type Offset = Long

object Offset:
  val genesis: Offset = 0L
  def apply(value: Long): Offset =
    require(value >= 0, s"negative offset $value")
    value

  given Ordering[Offset] = Ordering.Long

  extension (o: Offset)
    def value: Long = o
    def next: Offset = o + 1
    def <(b: Offset): Boolean = o < b
    def <=(b: Offset): Boolean = o <= b
    def >(b: Offset): Boolean = o > b
    def >=(b: Offset): Boolean = o >= b

/** A 15-minute market time unit, by index. */
opaque type Period = Int

object Period:
  def apply(index: Int): Period = index
  given Ordering[Period] = Ordering.Int
  extension (p: Period) def index: Int = p

/** A grid area: one site, one ledger. */
opaque type SiteId = String

object SiteId:
  def apply(name: String): SiteId = name
  given Ordering[SiteId] = Ordering.String
  extension (s: SiteId) def name: String = s

opaque type FactId = String

object FactId:
  def apply(value: String): FactId = value
  extension (f: FactId) def value: String = f
