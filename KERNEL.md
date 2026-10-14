# The Kernel

**Conservation-law problems, stated precisely: what the Safe Corridor shares with payments, telecom charging, trading limits, inventory and AI usage limits, and what each domain adds.**

Oct 14, 2026 · @Dumitru-Nicolae Marasoiu

## Orientation

The [README](README.md) develops one domain in depth: Nordic energy flexibility. This document steps back. It asks whether that problem is an instance of a wider class, what exactly the shared kernel is, which domains belong to it, and in what order they are worth taking on.

Short answer: yes, there is a class, but "conservation law" names only one of its four legs. **Conservation is the free part. The cost, and the interest, live in the bounds.**

The project began, in its first commit, as a specification built around cross-border FX payments, with telecom and energy as secondary mappings. Refocusing on energy was right for the flagship, because energy is where the kernel is *forced* rather than merely convenient. This document recovers the breadth without diluting the flagship.

## 1. The thesis, refined: four guarantees, four mechanisms

A first statement of the thesis was: *problems governed by a conservation law, in which distributed sites act locally, share one structure.* Checking it against the README shows that this is necessary but not sufficient.

**Conservation alone is coordination-free.** If every committed fact is balanced — its debits equal its credits — then any set of facts sums to zero, whatever order the facts arrive in and however the sites are partitioned. In the language of Bailis et al., conservation is *invariant-confluent*: merging two valid states that diverged from a common valid state always yields a valid state. A conservation law therefore never forces coordination. That is good news, and it is also proof that conservation is not where the problem lives.

**Conservation catches torn facts, not duplicated ones.** A balanced fact applied twice still sums to zero. Conservation detects a fact applied by halves, one leg without the other. It cannot detect a fact applied twice, or lost entirely. That takes a different mechanism: a cursor.

So the class stands on four legs, each with its own mechanism and its own failure it rules out:

| Guarantee | Rules out | Mechanism | Coordination cost |
| --- | --- | --- | --- |
| **Conservation** | value created or destroyed; a torn fact | Balanced facts, with transit and deferral accounts so that every fact balances *at the site that commits it* | None |
| **Exactly-once** | a fact applied twice, or lost | Cursor and aggregate committed atomically; cumulatives keyed by cursor | None between sites; atomicity inside one site only |
| **Bounds** | leaving the corridor | Escrow: the corridor's slack partitioned among sites as budgets | Only when a budget runs out |
| **Knowledge** | acting on a view that is wrong by an unknown amount | The two-sided bound: view ± budgets ± measurement error | None; it is computed |

The first two are free under partition. The third is the only one that ever needs a round trip, and only at its edges. The fourth turns staleness into a number someone can act on.

**The class, then:** *conserved quantities under linear bounds, changed by many sites that must act locally, and decided against a deadline before the truth is known.* "Conservation-law problems" remains a good short name, as long as it is read as conservation **plus** bounds.

## 2. Membership test

A problem belongs to the class when all five hold:

1. **Additive.** The quantity of interest is a sum of contributions from many sites: sums of integers, not maxima, medians or ratios.
2. **Conserved.** Every change can be written as a movement between accounts, so the total is auditable, including movements into explicit *losses*, *in transit* or *deferred* accounts.
3. **Bounded.** The safety rules are linear bounds on such sums — a floor, a ceiling, or both — possibly at several nested scopes.
4. **Local.** Sites must act without asking, because of latency, availability, partitions, or sheer fan-out.
5. **Deadline-bound.** Commitments are made before the truth is known, and a predictable bound by the deadline matters more than fast average convergence.

The near-misses show where the boundary is:

- **Uniqueness constraints** (one username, one seat): not additive. Partitioning the namespace looks like escrow, but the bound machinery does not apply.
- **Non-linear physical constraints** (AC power flow, voltage): not linear. This is why the README models the grid as one energy ceiling per area.
- **Analytics counters** (likes, views): additive, but nothing bounds them, so there is no invariant to protect and nothing to guarantee beyond eventual convergence.
- **One site is enough:** if a single strongly consistent database can hold the whole quantity within the latency and availability required, the kernel is unnecessary. The README's baseline-comparison phase exists to show where that line falls.

## 3. The primitive under everything: cursor and aggregate

The most practical piece of the kernel is the one most often built in production without a name: **store the aggregate and the source cursor together, atomically, in the destination.** The cursor is whatever the source calls a position: a Kafka offset, a log sequence number, a change-tracking version, a change-data-capture resume timestamp, a pagination bookmark. On restart, read the cursor back from the destination and resume the source from it. Delivery may be at-least-once; the *effect* is exactly once.

The README's snapshot is this same pair, published instead of stored: *"up to offset N, my cumulative contribution is C."* The projection's stored offset vector is the same pair again, with one cursor per source. The pattern is not an implementation detail of the project. It is the transport-level form of the guarantee.

### The algebra decides which delivery guarantee you need

What a stream carries decides what its transport must promise:

| What crosses the wire | Algebraic shape | Duplicates | Reordering | Delivery needed |
| --- | --- | --- | --- | --- |
| A delta: "+20 Wh" | commutative group | **harmful** | harmless | exactly-once, or deduplication by fact ID |
| A cumulative at a cursor: "C = 520 up to N = 4,210" | join-semilattice (latest cursor wins) | harmless | harmless | at-least-once |
| An overwrite or state transition: "pending → posted" | non-commutative | harmful unless keyed | **harmful** | in order, and exactly-once or deduplicated |
| An extremum: max, min | semilattice, but not invertible | harmless | harmless | at-least-once, but every correction forces recomputation from history |

The design move this table justifies: **turn deltas into cumulatives at a cursor before they cross a boundary.** Exactly-once then becomes a *local* property. The publishing site maintains the cumulative atomically with its own cursor, inside one strongly consistent ledger where atomicity is cheap, and everything that crosses the network is idempotent. Exactly-once has not been eliminated. It has been moved to where it costs nothing.

### Scalar and vector cursors

- A **scalar cursor** suffices when the source has one total order: a single log, a database's log sequence number, a ledger cluster's commit timestamp. TigerBeetle's change data capture is of this kind. It resumes from the timestamp of the last acknowledged event and publishes at-least-once, leaving deduplication to the consumer, which is exactly the situation this primitive exists for. It publishes to an AMQP broker, so a Kafka transport needs a bridge.
- A **vector cursor** is needed when the source is partitioned (one offset per Kafka partition) or when the consumer merges several sources (one offset per site). A vector cursor is a version vector: it states exactly which facts a state includes, so exactly-once delivery and causal tracking are one structure.

### Heartbeats: evidence of absence

A snapshot whose cursor advanced while its cumulative did not still carries information: *nothing happened at this site up to N.* An empty batch that advances the cursor turns absence of evidence into evidence of absence, the same move Calvin makes with empty epochs. For the bound, a recent heartbeat is worth as much as a recent change.

### From staleness to a number

Together, these pieces convert one kind of uncertainty into another until it can be acted on:

**time-staleness** — "my view is a few seconds old" → **cursor-staleness** — "I have everything up to offset N, and what I lack is exactly what came after it" → **value-staleness** — "what came after is at most D below and U above, because that is the budget".

The cursor makes the gap *precise*; the budget makes it *small*. Production systems commonly have the first link and lack the second. The second link is this project's contribution.

## 4. How the domains differ: seven dimensions

The kernel is shared. Domains differ along a few dimensions, and each dimension is a feature the kernel must support once some domain demands it.

| Dimension | Values | Where it first bites |
| --- | --- | --- |
| **Bound shape** | floor only · ceiling only · corridor | A corridor makes slack finite: `Σ(D + U) ≤ C − F` |
| **Bound scope** | one global bound · nested scopes whose binding sides differ | Energy: a global floor and a local ceiling |
| **Who moves the quantity** | actors only, whose actions can be refused · actors and the environment | Energy dropouts, inventory shrinkage: a budget becomes an *assumption* backed by reserve, not a permission |
| **Truth model** | the ledger is the truth · the ledger is a claim | Energy baselines, any usage metered after the fact: measurement error `ε` |
| **Time** | carried balance · perishable per period · coupled across periods | Energy rebound and deferred load |
| **Clock** | latency budget · hard deadline | Payment cut-offs, gate closure, end of day |
| **Units** | one · several, linked | Energy settled into money; currency pairs |

The third row needs its own paragraph, because it is the dimension the original financial framing hid. In payments every change is a decision, and a decision can be refused: escrow works *by refusing*. In energy the environment moves the quantity — a car unplugs — and there is nothing to refuse. A down-budget for that kind of deviation is therefore **an assumption backed by local reserve**: the site promises to compensate locally up to `D`, and the guarantee holds only while that reserve lasts. Even when partitioned, a site always knows locally, and at once, when its own deviation has outrun its budget. What it cannot do then is tell anyone. The honest form of the guarantee is conditional on the environment: detection is local and immediate, even when reporting is not.

## 5. The family

| Domain | Quantity | Bound | Moved by | Truth | Time | Fit |
| --- | --- | --- | --- | --- | --- | --- |
| **Nordic energy flexibility** (flagship) | delivered Wh per period | corridor: global floor, local ceiling | actors and environment | claim (baseline) | perishable, coupled (rebound) | Strongest: every dimension is active |
| **Cross-border FX and treasury** (origin) | currency position per value date | floor (safeguarding); corridor (net open position) | actors | truth, but expected inflows are claims | carried, per value date | Strong; adds linked multi-currency transfers |
| **Telecom prepaid charging** | subscriber credit | floor at zero | actors | truth (usage measured by the node) | carried | The classic one-sided case, with decades of production behind it |
| **Pre-trade risk limits across gateways** | net position per instrument or account | corridor around zero: long and short limits | actors; fills partly the market | truth, with open orders as pending | intraday | A clean two-sided analog in a very different latency regime |
| **AI usage limits across regions** (tokens, spend, rate) | tokens or currency per window | ceiling; floors where capacity is reserved for a customer | actors | **claim**: a streaming request's true cost is known only when it ends | perishable windows | Strong and timely; forces reserve-then-settle with estimate error, and hierarchical escrow at large fan-out |
| **Inventory and ticketing** | units per item or event | floor at zero | actors and environment (shrinkage, damage) | claim (counts drift) | carried; holds with timeouts | Good teaching example; covered by the others |
| **Ad budget pacing** | spend and impressions per day | corridor: budget ceiling, delivery floor for guaranteed deals | actors; prices settle after the auction | claim until billed | perishable per day | Two-sided; overlaps energy |
| **Certificates and hourly matching** (guarantees of origin, 24/7 clean energy) | certified Wh per hour | ceiling on claims: no double counting | actors | claim (metered) | perishable per hour | Conservation-centred; the natural sequel to energy |

Two observations stand out:

- **Two-sided bounds are not exotic.** They appear wherever a party owes delivery *and* must not overshoot: energy, trading limits, ad pacing, net open positions in FX. The original one-sided framing understated the class.
- **"The ledger is a claim" is spreading.** Anything metered after the fact has it: energy, AI usage, ad spend, counted inventory. Measurement error consuming autonomy, the README's `ε` result, is not an energy curiosity.

## 6. What validating the thesis surfaced

Re-reading the README's guarantee against the wider family exposed one gap in the core claim and one in how it is stated. Both close with mechanisms the design already has.

### The budget contract needs three rules to be true

The README bounds `δ_s`, what site `s` committed after its last snapshot, by that site's budgets. That holds for a receiver only if the receiver's idea of the site's last snapshot and budgets matches the site's own. Under partition the two can differ. Three rules close the gap:

1. **Budgets are consumed, and re-armed only by a grant.** Suppose Oslo-N has a down-budget of 20 kWh, uses all of it, and publishes a fresh snapshot. If publishing re-armed the budget, Oslo-N could drop another 20 kWh, and a partitioned receiver still holding the *older* snapshot would be wrong by 40 kWh against a bound of 20. A snapshot therefore reports three things: the cursor, the cumulative, and the **remaining** budget on each side.
2. **New slack must be visible before it is used.** A site may always tighten its own budget. It may loosen it only once every view relying on that budget can account for the change. This asymmetry is the heart of the demarcation protocol (Barbará & Garcia-Molina, 1992). The README's first lever, *request fresh snapshots*, works by re-anchoring budgets on the fresh snapshot, which is a grant, and that is why it needs the network. Under partition no grant can be made visible, so **autonomy under partition is finite by construction**: a site can spend what it holds, and no more. That is the right answer, not a limitation.
3. **Moved slack is counted in transit.** When Bergen-W gives 10 kWh of down-budget to Oslo-N, a receiver holding Bergen-W's *new* snapshot and Oslo-N's *old* one would count those 10 kWh nowhere, and its bound would be too tight to be true. The fix is the one money already uses: the donor reports the slack as **in transit** until the recipient's snapshot that includes it is known, and snapshots carry the cursor dependencies — the version vector — that let a receiver tell whether its cut is consistent.

The third rule is the satisfying one. **Slack is itself a conserved quantity**, and the allocator's invariant `Σ(D + U + 2ε) ≤ C − F` is a conservation law over it. The kernel has to be applied to its own budgets. That is the strongest evidence that conservation belongs in the thesis, just not where it was first placed.

The energy domain eases rules 1 and 2 considerably, because the market clock supplies natural epochs. Budgets for a period can be granted at activation time, 7.5 minutes before the period starts. Every site then knows every grant before any of it can be used, and the budgets expire with the period, like a lease. **A perishable quantity is a gift to escrow.**

### Decided and undecided deviation

The second refinement is the third dimension of §4. A budget that bounds refusable actions is a permission. A budget that bounds environmental deviation is an assumption backed by reserve. The README's tables treat both alike. The verification claims should separate them: *the bound holds whenever every site's uncontrolled deviation stays within its local reserve.* The simulator should also test what happens when it does not, and check that the breaching site always knows locally, at once.

## 7. Backlog

**Admission rule: a domain earns a place only if it forces a kernel feature that no earlier domain forced.** That keeps the kernel honest, since every feature has a domain that needed it, and it keeps the backlog finite.

### Kernel features

| # | Feature | Forced by |
| --- | --- | --- |
| K1 | Balanced facts; transit and deferral accounts | every domain; deferral by energy |
| K2 | Cursor and aggregate: cumulatives at a cursor, atomic projection, scalar and vector cursors, heartbeats | every domain |
| K3 | Two-sided bound with measurement error | energy |
| K4 | Escrow allocator: conservation of slack, the three budget rules | energy (two-sided); telecom (one-sided) |
| K5 | Reserve-then-settle: pending amounts with a timeout and an estimate error | energy (dispatched, not yet metered); AI usage (streaming requests); trading (open orders) |
| K6 | Nested scopes and hierarchical escrow | energy (feeder within area within portfolio); AI usage and telecom (node within region within global) |
| K7 | Periods and time-coupling | energy |
| K8 | Linked multi-unit transfers | energy settled into money; FX |
| K9 | A domain-agnostic deterministic simulator and invariant suite | all; useful in its own right |
| K10 | Worst-case versus probabilistic `ε` | energy, AI usage, ad pacing: the central research question |

### Domain adapters, in order

- **Now: energy.** The README's phases 1–8. It exercises K1–K7, K9 and K10, more than any other single domain.
- **Next: one second domain, chosen to stress what energy does not.**
  - *AI usage limits across regions* tests K5 under extreme fan-out and K6 as a hierarchy several levels deep. It is also the most legible example for people who build AI systems.
  - Or *pre-trade risk limits*, a clean two-sided corridor in which every movement is decided, which isolates the escrow from measurement noise.
- **Later: FX and treasury**, for K8 and to close the loop with where the project began; then **certificates and hourly matching**, as the sequel to energy.
- **Not planned as implementations: inventory, ad pacing, telecom.** Each is fully covered by the features above, so they stay as explanatory mappings. Telecom remains the best first illustration of the idea for newcomers.

One spin-off is worth noting. K9, the deterministic simulator with its falsifiable invariant suite, is domain-agnostic and could stand alone as a library for testing bounded-error systems on Cats Effect.

## 8. Open questions

- [ ] Is "conserved quantities under linear bounds" the right public name for the class, or is the cursor-and-aggregate primitive the more useful face of it?
- [ ] For K6: does hierarchical escrow need a new bound, or is each level the same bound applied recursively, with the parent acting as the allocator?
- [ ] For K5: does the estimate error on a pending reservation compose with `ε` additively, or is it the same quantity observed earlier?
- [ ] How should the simulator express environmental deviation beyond reserve: as an injected fault, like a partition, or as a property of the asset model?
- [ ] Which second domain gives more to the kernel: AI usage limits (scale and hierarchy) or trading limits (a clean two-sided corridor)?
