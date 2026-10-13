# Bounded-Error Global Positions — Project Specification

Oct 13, 2026 · @Dumitru-Nicolae Marasoiu

## Orientation

**The problem in one sentence:** keep several regions operating continuously and independently, while maintaining a global view of money whose error is provably bounded at every moment, and coordinating only when local budgets run out.

This is a personal research and engineering project. It reuses proven primitives (a financial ledger database, a log/streaming platform) and builds the layer above them: the guarantee layer. The theory behind it is known; the contribution is an open, end-to-end, verified composition applied to a real problem that financial institutions keep reinventing privately.

How to read this document:

1. **The financial domain** grounds the problem in cross-border FX payments.
2. **A second domain** (telecom) shows the same pattern outside finance, so the core stays generic.
3. **A third domain** (Nordic energy flexibility) shows where the pattern is growing right now, and why it needs a two-sided bound.
4. **The generic problem and the guarantee** define the model logically, with a worked example.
5. **Mechanisms, architecture and verification** describe how it is built and proven.
6. **Simplifications, prior art and phases** keep the scope honest and finishable.

The domain is deliberately simplified. The technical core is where the depth goes.

## The financial domain: cross-border FX payments

A cross-border FX platform must know, per currency and per location, how much money it has and needs before each settlement deadline. Its positions are fragmented, while its decisions need a global view.

**The business.** The platform serves banks and fintechs that offer FX to their own customers. It quotes rates, books client trades, nets them, and covers the resulting exposure with liquidity providers (large banks) within risk limits. Client money must then be delivered in the right currency, at the right place, by the right date.

**The concepts that matter:**

- **Positions:** per currency, account and value date: client liabilities, client funds held, house funds, open FX exposure, expected inflows and outflows.
- **Covering:** hedging net exposure with liquidity providers, typically within a net-open-position limit per currency.
- **Settlement:** delivery happens on value dates (spot is commonly T+2, some pairs T+1) through payment systems with cut-off times. Paying out before receiving creates settlement risk.
- **Funding (cash positioning):** moving money to where it will be needed before the cut-off: same currency between accounts (a payment), or across currencies (an FX trade).
- **Safeguarding:** a payment or e-money institution must keep client money separate from its own, in designated accounts, not used for operations. Rules vary by jurisdiction.
- **Operating accounts:** currency accounts held at correspondent banks, functionally like nostros. Banks may offer intraday credit on house accounts; not on safeguarding accounts.
- **In-transit:** money that has left one account but not yet arrived at another is recorded in a transit account, so it is never lost from the books.

**Why the deadline matters more than speed.** The clocks are cut-off times. A treasurer, or a regulator, needs to know that by the USD cut-off the projected position is accurate within a known amount. Fast but unpredictable convergence is not useful to them; a predictable bound is.

**Why availability is money.** Halting a region because another is unreachable means rejecting client payments. A region that can keep operating safely within known limits keeps the business running.

## A second domain: telecom prepaid credit

The same pattern has run in telecom networks for decades: a central system grants quota, and network nodes spend it locally without asking each time.

A prepaid subscriber has a balance in a central charging system. When they start a call or a data session, the network node serving them requests a slice of quota (for example 5 minutes or 50 MB) and spends it locally. When the slice runs out, it asks for more. If the central system is briefly unreachable, the node can keep serving within its granted slice, and the subscriber can never spend more than their balance.

The mapping to the financial case is direct:

| Telecom | FX payments | Generic |
| --- | --- | --- |
| Subscriber balance | Account or currency position | Global quantity |
| Network node | Region | Site |
| Granted quota slice | Escrowed budget | Local budget |
| Requesting more quota | Requesting rights from another region | Selective coordination |
| Never exceed balance | Never breach safeguarding or go negative | Invariant |

Other examples of the same shape: ad budget pacing across regions, global API rate limits, inventory reserved across warehouses, and usage-based billing with a bounded amount of unbilled usage. The core of this project is written for the generic column; the financial domain is the worked example.

## A third domain: energy flexibility in the Nordic power system

As electricity replaces fossil energy and more production becomes intermittent, the grid needs flexibility: consumption and storage that can shift up or down on request. In Norway, hydropower has long provided most of it. Increasingly, it also comes from many small, distributed resources: heat pumps, EV chargers, home and industrial batteries, and flexible industrial loads.

**The business.** An aggregator (in market terms, a balancing service provider) bundles thousands of small resources into a portfolio and sells its flexibility. Buyers are the transmission system operator (Statnett in Norway), which uses reserves such as mFRR to keep the system in balance, and distribution system operators, which use local flexibility to relieve congested grids instead of building new capacity.

**The concepts that matter:**

- **Bids and activation:** the aggregator commits a volume (for example 5 MW up-regulation) for a market time unit. When the operator activates it, full delivery is due within a short deadline; for mFRR, within 15 minutes.
- **15-minute resolution:** Nordic imbalance settlement runs on 15-minute periods. Delivery is measured and settled per period, and the metering of small resources is not always that granular yet.
- **Aggregation thresholds:** small resources only qualify when bundled. Opening mFRR to aggregated resources below 1 MW is an active regulatory topic in Norway.
- **Grid limits:** activating flexibility for system balance must not overload a local distribution grid. The transmission operator does not see those grids in full, so local limits must be respected at the edge.
- **Roles:** the party responsible for imbalances (balance responsible party) and the party providing balancing (balancing service provider) are increasingly separate, so delivered flexibility must be accounted for between them.

**The mapping:**

| Energy flexibility | FX payments | Generic |
| --- | --- | --- |
| Portfolio's committed volume for a period | Currency position | Global quantity |
| Sub-portfolio or edge controller (per grid area, per site) | Region | Site |
| Share of the bid a sub-portfolio may deliver or withhold on its own | Escrowed budget | Local budget |
| Shifting delivery to another sub-portfolio when assets drop out | Requesting rights from another region | Selective coordination |
| Deliver at least the activated volume; never exceed a feeder's limit | Never breach safeguarding or go negative | Invariant |
| Activation sent but not yet confirmed by metering | Money in transit | In-flight state |

**Why it is the same problem.** Each edge controller acts locally, because assets must respond within minutes even when the central platform is slow or unreachable. The central view of "how much are we actually delivering right now" lags, because telemetry is delayed, sampled or missing. The question that matters is the one this project asks: by the end of this 15-minute period, is delivery provably within a known amount of the commitment?

**Why it is harder, and why that is useful.** In finance, the guarantee is one-sided: inflows only make things safer. Here there are two invariants pointing in opposite directions. Under-delivery breaks the commitment to the operator, and over-delivery in one area can overload a local grid. The project therefore needs both a floor and a ceiling: each site publishes both its budget to deviate downward and its budget to deviate upward. This turns the open question about bounding unseen inflows from optional into required, and makes the energy domain the natural second worked example once the financial one works.

**What changes at the boundary.** Energy cannot be in transit the way money can, and physical delivery is confirmed only by metering. The conservation check becomes a reconciliation: scheduled, activated and measured volumes per period must explain each other, with every difference accounted for as an imbalance.

## The generic problem

The model has five ingredients: sites, logs, quantities, budgets and invariants. Everything else is built from these.

1. **Sites.** A small number of regions (for example London, New York, Singapore). Each is internally strongly consistent and durable. Between sites, links can be slow or partitioned.
2. **Logs.** Each site has its own append-only log of committed facts (transfers). A position in a log is an offset. Facts are never edited; corrections are new facts.
3. **Quantities.** Values derived from the logs, such as the USD position at a given account and value date. A quantity's true value is the sum of all committed facts across all sites.
4. **Budgets.** Each site holds a budget per quantity: the amount by which it may change that quantity without asking anyone. Budgets can be moved between sites.
5. **Invariants.** Rules that must never be broken, such as "safeguarded USD ≥ client USD liabilities" or "no account below zero".

Two kinds of operations follow from this:

- **Safe operations** cannot break an invariant whatever happens elsewhere, for example incoming credits. They need no coordination.
- **Unsafe operations** could break an invariant if combined with concurrent operations elsewhere, for example outgoing debits. They are allowed locally only within the site's budget; beyond it, the site must coordinate.

Each site keeps a **global view**: its best knowledge of every quantity, built from the other sites' published state. The question this project answers is how far that view can be from the truth, and how to guarantee it never drifts further than a stated limit.

## The guarantee

**The global view is exact up to each site's last snapshot, and below the truth by at most the sites' outflow budgets granted since then.**

Each site periodically publishes a snapshot: "up to offset N, the cumulative change to quantity q is C". A receiver keeps only the highest-offset snapshot per site and sums them. Write the true value, the view, and the error as:

```latex
\text{true}(q) = \sum_{s} C_s(q,\ \text{now})
\qquad
\text{view}(q) = \sum_{s} C_s(q,\ N_s)
\qquad
\text{error}(q) = \sum_{s} \big[\, C_s(q,\ \text{now}) - C_s(q,\ N_s) \,\big]
```

The error comes only from what each site committed after its last snapshot. Two observations make it bounded:

- **Outflows** after the snapshot are limited by the site's outflow budget B\_s. So the unseen decrease is at most the sum of the budgets.
- **Inflows** are safe operations and unbounded, but they only make the truth higher than the view. For a floor-type invariant, that error is on the safe side.

The result is a one-sided guarantee, which is exactly what safety needs:

```latex
\text{true}(q) \;\ge\; \text{view}(q) - \sum_{s} B_s
```

**Worked example (USD, two sites):**

| Site | Last snapshot offset | Cumulative USD at snapshot | Outflow budget since snapshot |
| --- | --- | --- | --- |
| London | 1,000 | +5.0M | 0.5M |
| New York | 700 | +3.0M | 0.3M |

The view is 8.0M, and the guaranteed minimum is 8.0M − 0.8M = 7.2M.

- If the safeguarding floor is 6.5M, the invariant is **provably safe**, whatever happened since the snapshots.
- If the floor is 7.5M, it is **not provably safe**. The system acts: it asks for fresh snapshots (shrinking the uncertainty), reduces budgets (shrinking future uncertainty), or proposes a funding move.

This is why budgets play two roles at once: they permit local action, and they bound global error. Shrinking budgets trades local autonomy for tighter global knowledge.

## Core mechanisms

Five mechanisms implement the guarantee. Each is small; the value is in how they compose.

**1. Snapshots keyed by offset, not by time.** A snapshot says "everything up to offset N", which is deterministic, so no clocks are needed to define a batch. A receiver keeps the snapshot with the highest offset per site and ignores the rest. Duplicates and reordering change nothing: the merge is a max-register, one of the simplest CRDTs. This removes the batch-idempotency problem entirely.

**2. Escrow budgets.** Each site holds an outflow budget per quantity. It approves unsafe operations locally while the budget covers them. When it runs short, it requests budget from another site: pairwise coordination, only when needed, never global consensus. Rebalancing policy (where budget should live) is the main open design space.

**3. Selective coordination.** Operations are classified once: safe (commute, cannot break an invariant) or unsafe. Only unsafe operations ever touch budgets or coordination. Closing an account or certain reversals need full coordination and are rare.

**4. Exactly-once projection.** The projection commits its derived state and the vector of source offsets in one atomic write. On restart, it resumes from the stored vector. That offset vector is also a version vector: it records exactly which facts from each site have been applied, so exactly-once and causal tracking are the same structure.

**5. Conservation through in-transit accounts.** A transfer between sites is recorded as a debit at the source and a credit to an in-transit account, then later moved from in-transit to the destination. Total money is conserved at every moment, even while the transfer is in flight or a link is down.

**Priority by magnitude.** Large transfers can trigger an immediate snapshot, so big moves become visible within seconds, while small ones ride the regular interval. Priority affects visibility latency; the bound itself comes from budgets.

**Two kinds of replay.** Semantic replay rebuilds state from any order of facts consistent with causality. Execution replay reproduces one site's actual apply order, recorded as a list of fact IDs. Comparing many random valid orders tests whether operations assumed to commute really do.

## Architecture

**Each site's ledger decides alone within its budget; snapshots cross between sites, and each projection keeps a global view with a guaranteed bound.**

&#91;embedded content: architecture · two sites, one transport, one simulator\]

Budget requests between sites are the only coordination and are not drawn.

| Component | Role | Technology |
| --- | --- | --- |
| Ledger | System of record per site; decides and commits transfers | TigerBeetle (simulated in early phases) |
| Snapshot publisher | Turns committed facts into offset-keyed cumulative totals | Project code, fed by TigerBeetle change data capture |
| Transport | Carries snapshots (priority) and transfers (bulk) between sites | Kafka (simulated in early phases) |
| Projection | Global view, error bound, exactly-once state | Project code |
| Simulator | Deterministic time, faults and invariant checks | Project code on Cats Effect or ZIO |

The project's own code is the guarantee layer: publisher, projection, budgets and simulator. Ledger and transport are reused, not rebuilt.

## Verification

Correctness is proven by deterministic simulation: every failure scenario is generated from a seed and can be replayed exactly.

The simulator controls time, message delivery, partitions, crashes and reordering as effects (Cats Effect or ZIO make this natural). A failing seed reproduces the same run every time, which turns rare distributed bugs into ordinary debuggable ones. This is the approach FoundationDB and TigerBeetle use.

Invariants checked after every simulated step:

- [ ] **Conservation:** total money across all sites and in-transit accounts equals total money created.
- [ ] **Bound holds:** the true value is never below the view minus the sum of budgets.
- [ ] **No invariant breach:** no floor (safeguarding, non-negative balance) is ever violated.
- [ ] **Exactly-once:** no fact is applied twice; no committed fact is lost.
- [ ] **Convergence:** after partitions heal and traffic stops, all views equal the truth.
- [ ] **Reconciliation:** projected positions match the sum of what each site's ledger committed.

A further property test replays many random orders of the same causal history and checks that the resulting state is identical. A difference reveals an operation wrongly assumed to commute.

## Explicit simplifications

This is a model of the domain, not a treasury system. These simplifications are deliberate and stated up front:

| Area | Real world | This project |
| --- | --- | --- |
| Data | Real clients, banks, accounts | Synthetic data, generic names |
| FX pricing | Live rates, spreads, quotes | Out of scope; trades arrive with a rate |
| Covering | Hedging strategies with liquidity providers | Out of scope; cover trades are just facts in the log |
| Cut-offs | Calendars per currency, holidays, time zones | A simplified fixed cut-off per currency |
| Value dates | T+0, T+1, T+2 per pair | One value-date field, used for grouping |
| Safeguarding | Jurisdiction-specific rules | A per-currency floor invariant |
| Bank credit | Negotiated intraday lines | An optional fixed credit allowance per account |
| Regulation | Reporting, audit, licences | Out of scope |
| Energy markets | Bidding, prices, prequalification, TSO-DSO coordination | Out of scope; activations arrive as facts with a volume and a deadline |
| Physical delivery | Asset response curves, metering, telemetry quality | Simulated delivery with configurable delay, loss and dropout |

No confidential information from any employer is used. All concepts come from public material and general knowledge.

## Prior art and positioning

**Not new theory, but an open, end-to-end, verified composition of known theory, applied to a problem institutions keep reinventing privately.**

| Work | Year | What it contributes here |
| --- | --- | --- |
| TACT (Yu & Vahdat) | 2000 | Consistency with explicit numerical error bounds; the closest prior art to the guarantee |
| Bailis et al., Coordination Avoidance | 2014 | Invariant confluence: which operations need coordination |
| Balegas et al., bounded counters / Indigo | 2015 | Escrow-style numeric invariants on eventually consistent stores |
| Shapiro et al., CRDTs | 2011 | Convergent merge, including max-registers and counters |
| Thomson & Abadi, Calvin | 2012 | Deterministic ordering in epochs; explicit absence via empty batches |
| Barbará & Garcia-Molina, Demarcation protocol | 1992 | Local action within distributed limits |
| O'Neil, Escrow transactional method | 1986 | The original escrow idea |

Commercial treasury management systems perform cash positioning, but they are closed source and typically batch or near-real-time. From public material, they do not expose formal error guarantees under partition.

In practice, payment and FX firms tend to build this layer in-house from databases, outbox patterns and streams, each with its own implicit guarantees. This project makes the guarantees explicit and testable.

## Phases

**First milestone: two simulated sites, each with its own log of committed transfers, projected exactly-once into a global view that reports its maximum possible error at every moment, with in-transit transfers keeping money conserved.**

The milestone is done when a simulation test partitions and heals the sites and shows that money is conserved and the reported bound is never violated.

1. **Single-site ledger and projection.** Immutable facts, idempotency by transfer ID, derived positions, atomic state-plus-offset commit.
2. **Two sites with snapshots and the simulator** (the first milestone). Offset-keyed snapshots, max-register merge, in-transit accounts, deterministic simulation with the invariant checks.
3. **Escrow budgets.** Outflow budgets per site, local approval within budget, pairwise budget requests, the one-sided bound computed live.
4. **Rebalancing policy and partition behaviour.** Where budgets live, proactive versus on-demand rebalancing, what happens to debits beyond budget during a partition.
5. **Real primitives.** Swap the simulated ledger for TigerBeetle per site and the simulated transport for Kafka, using TigerBeetle's change data capture as the source of facts and balances.
6. **Baseline comparison.** A strongly consistent home-region design, measured against the escrow design for availability and latency under partitions.

## Open questions and reading

Open questions:

- [ ] Cats Effect or ZIO for the effect system and simulator?
- [ ] Should the bound be tightened by also publishing a per-site maximum unseen inflow? For floor-type financial invariants a one-sided bound is enough; the energy domain, with both a delivery floor and a grid ceiling, needs both sides.
- [ ] Which rebalancing policy first: on demand only, or proactive based on observed outflow rates?
- [ ] How should two-phase transfers (pending, then post or void) be ordered in the projection when large and small transfers travel on different paths?
- [ ] Does TigerBeetle's change data capture expose everything needed for offset-keyed snapshots, or must the projection derive them?

Reading list, in suggested order:

1. Yu & Vahdat, *Design and Evaluation of a Conit-Based Continuous Consistency Model for Replicated Services* (TACT), 2000.
2. Bailis et al., *Coordination Avoidance in Database Systems*, 2014.
3. Balegas et al., *Putting Consistency Back into Eventual Consistency* (Indigo), 2015, and their bounded counter paper.
4. Shapiro et al., *Conflict-free Replicated Data Types*, 2011.
5. Thomson et al., *Calvin: Fast Distributed Transactions for Partitioned Database Systems*, 2012.
6. O'Neil, *The Escrow Transactional Method*, 1986.
7. [TigerBeetle Change Data Capture documentation](https://docs.tigerbeetle.com/operating/cdc/).
