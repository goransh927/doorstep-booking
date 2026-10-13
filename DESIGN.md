# Design notes

```
 client ──► POST /bookings ──► BookingService
                                  │ 1. idempotency lookup (booking.idempotency_key)
                                  │ 2. validate (service, pincode→zone, slot, window)
                                  │ 3. snapshot read: qualified techs + that day's bookings
                                  │ 4. SchedulingPolicy ranks free techs (fewest that day, then id)
                                  ▼
                   for each candidate:  BEGIN
                                        SELECT … FROM technician WHERE id=? FOR UPDATE
                                        re-check overlap (incl. buffer)
                                        INSERT booking          ◄── EXCLUDE constraint backstop
                                        COMMIT  → 201
                   none left → 409 NO_AVAILABILITY
```

## 1. Concurrency choice

**What the code does.** Two layers:

1. **Pessimistic row lock per technician.** Each assignment attempt runs in its own short
   transaction: lock the technician row (`FOR UPDATE`), check for an overlapping live booking,
   insert. Two requests for the same technician serialise on that row; the second sees the first's
   committed booking, skips that technician and tries the next candidate. Locks are held for a
   few milliseconds, one technician at a time, so there is no lock-ordering deadlock.
2. **Exclusion constraint** `EXCLUDE USING gist (technician_id WITH =, tstzrange(start_time,
   blocked_until) WITH &&) WHERE status <> 'CANCELLED'`. The database refuses to commit two
   overlapping live bookings for one technician, no matter what code path (a bug, a script, a
   future endpoint) writes them. Cancelling flips the status and the range leaves the constraint
   immediately. The code treats a violation as "this technician is taken, try the next".

**Why not the alternatives on their own.**

| Option | Why not (alone) |
|--------|-----------------|
| Unique constraint on `(technician_id, start_time)` | Only catches identical starts. A 60-min job at 10:00 and a 30-min job at 10:30 do not collide on any key; neither do buffer violations. Would need slot-row materialisation (one row per technician per 30 min) — workable, but more rows and coupling to the grid. |
| Optimistic versioning (`technician.version`) | Correct, but under contention for a popular slot every loser retries the whole read-rank-write, and the retry rate climbs exactly when traffic spikes. A lock makes losers wait milliseconds instead. |
| `SERIALIZABLE` isolation | Correct, but aborts are coarse (predicate locks on range scans), retry logic leaks everywhere, and false conflicts grow with volume. |
| Distributed lock (Redis/ZooKeeper) | Adds a second system whose failure modes (lease expiry during GC pause, split brain) can still allow a double-booking. The database is already the source of truth and already has the lock. |

The exclusion constraint is the real guarantee; the row lock makes the common case clean
(no exception-driven control flow, deterministic "next candidate" behaviour).

**Where it breaks.**
- *Single primary.* Both layers rely on one PostgreSQL primary. Fine to thousands of writes/s;
  multi-region active-active writes would need partitioning so a technician lives in one region.
- *Hot technician.* All bookings for one technician serialise. Irrelevant at one technician's
  scale (a dozen jobs a day), but a "fake" shared technician (e.g. a pool modelled as one row)
  would bottleneck.
- *Fairness under races.* Ranking uses a snapshot, so concurrent requests can both pick the same
  "least-loaded" technician; the loser moves to the next one. Correct, but load balance is
  approximate.
- *Long candidate lists.* Losers walk candidates sequentially. With ~10 technicians per zone/skill
  this is cheap; with hundreds, re-rank after N failures or pick from the top-k at random to spread
  contention.

## 2. Scale: 50 cities, 5,000 technicians, 200k bookings/day, 10x Monday spike

Back of envelope: 200k/day ≈ 2.3 writes/s average; assume 70% in a 10-hour window and 10x Monday
mornings → **~50–60 booking writes/s at peak**, and availability reads perhaps 20–50x that
(**1–3k reads/s**). One well-sized PostgreSQL handles the writes comfortably; reads are the problem.

What changes first, in order:

1. **Availability reads move off the primary.** Read replicas for `/availability` (see §3). Measure:
   primary CPU, p95/p99 of `/availability`, connection-pool wait time, replica lag.
2. **Connection management.** 10x spikes exhaust per-instance pools before the DB is saturated.
   PgBouncer (transaction mode) in front of PostgreSQL, horizontal app replicas behind the LB.
   Measure: Hikari `pending` threads, `connections_active` vs `max_connections`.
3. **Reference data cached in-process.** Services, zones and technicians change rarely; cache with
   short TTL / change events. Removes 3–4 queries per request. Measure: queries per request.
4. **Partitioning `booking` by month** (and archiving `COMPLETED`/`CANCELLED` older than N months).
   200k/day is ~73M rows/year; the exclusion index and `(technician_id, start_time)` stay small per
   partition. Measure: index size vs RAM, autovacuum lag, query plans on the overlap check.
5. **Rate limiting moves to the gateway / Redis**, keyed per phone and per IP, so it is global.
6. **Only if needed: shard by city.** Cities are independent (a technician never crosses cities),
   so `city_id` is a natural shard key with zero cross-shard transactions. I would not do this
   until a single primary's write latency or storage becomes the limit.

Metrics to watch continuously: booking p99 latency and error rate by `code`, ratio of
`NO_AVAILABILITY` to bookings (demand vs supply, also a product signal), lock wait time
(`pg_stat_activity` wait events on the technician rows), exclusion-constraint violations (should be
~0; non-zero means a code path skipped the lock), DB CPU/IO, replica lag.

## 3. Keeping `GET /availability` fast without showing gone slots

- **Compute from a compact read model, not by scanning bookings.** Keep, per technician per day, a
  bitmap of 30-min cells (24 cells for 08:00–20:00) with occupancy + buffer pre-applied. Availability
  for a (zone, skill, date) is then an OR across ≤ a few dozen small bitmaps and a sliding-window
  check for the service's duration — microseconds, no SQL joins.
- **Where the bitmaps live.** Redis hash per `(zone, date)`, updated in the same flow that commits a
  booking/cancel (via the outbox below so a crash can't skip it), and rebuilt from PostgreSQL on a
  miss. Alternatively an in-process cache per instance invalidated by a pub/sub event.
- **Short TTL HTTP caching** (`Cache-Control: max-age=5`) on the response for anonymous browsing
  absorbs Monday-morning refresh storms.
- **Never trust the cache for correctness.** The booking path always re-checks under the row lock
  and the exclusion constraint. The cache can only be *stale*, never *wrong in a harmful way*: a
  stale slot costs the customer one `409 NO_AVAILABILITY`, after which the client refreshes
  availability. Measure the "listed-but-gone" rate (409s on slots shown < N seconds ago); if it
  rises, shorten TTLs or push invalidations faster.
- **Optional soft hold.** For a smoother UX, `POST /holds` reserves a slot for 2–3 minutes during
  checkout (a booking row with status `HELD` and `expires_at`, inside the same exclusion
  constraint). That turns "gone at confirm time" into "gone at selection time".

## 4. Notifications (slow, flaky SMS provider)

Never call the provider inside the booking request.

1. **Transactional outbox.** In the same transaction that inserts the booking, insert an
   `outbox_event(id, type=BOOKING_CONFIRMED, payload, created_at, status)` row. The booking and
   the intent to notify commit atomically; there is no "booked but never notified" or "notified
   but rolled back".
2. **Relay.** A worker polls `outbox_event` (`FOR UPDATE SKIP LOCKED`, so several workers can run)
   or uses CDC (Debezium) to publish to a queue (SQS/Kafka/RabbitMQ).
3. **Consumers per channel.** SMS/WhatsApp consumer and push consumer scale and fail
   independently. Each send uses `booking_id + channel` as an idempotency key (stored in a
   `notification` table and, where supported, passed to the provider) so redelivery never texts
   the customer twice.
4. **Failure handling.** Per-call timeout (2–3 s), exponential backoff with jitter, circuit breaker
   around the provider, fallback provider or WhatsApp→SMS fallback, dead-letter queue after N
   attempts with an alert. A booking's notification status is visible to support.
5. **The API stays fast and green** even if the provider is down for an hour; the backlog drains
   when it recovers. Metrics: outbox age (oldest unsent), send success rate by provider, DLQ depth.

## 5. Unplanned change: reassign a sick technician's jobs

**What makes it easy**
- Assignment is one column (`booking.technician_id`) and all the rules live in `SchedulingPolicy`
  as pure functions. "Find a replacement for this booking" is exactly the existing
  `rankFreeTechnicians` call with the sick technician excluded.
- The lock-then-check-then-write path and the exclusion constraint already protect a
  reassignment `UPDATE` the same way they protect an `INSERT`, so moving jobs cannot double-book
  the receiver.

**What makes it hard / what I'd add**
- **No availability model for technicians.** Today a technician is either in the table or not.
  Need a `technician_unavailability(technician_id, from, to, reason)` table, consulted by
  `isFree` (and blocking new bookings).
- **No history.** Overwriting `technician_id` loses who was originally assigned. Add a
  `booking_assignment` history table (or event log) for audit and notifications.
- **Partial success.** Some jobs may have no replacement. The operation should be per-booking,
  reporting `reassigned / could-not-reassign` so ops can call those customers; it should not be
  all-or-nothing.
- **Ordering and fairness.** Reassign earliest jobs first; lock receiver technicians in id order to
  avoid deadlocks if done in one transaction (or do one booking per transaction, like create).
- **Notifications.** Customer and both technicians need messages — falls out of the outbox design.

## 6. Next week, in priority order

1. **Outbox + notifications** (§4): the business needs confirmations and it is the first
   real-world failure mode.
2. **Ownership on cancel** (phone + OTP) — today anyone with an id can cancel.
3. **Technician unavailability + reassignment** (§5), because sick days happen on day one.
4. **Observability**: Micrometer metrics (booking outcomes by code, lock wait, outbox lag),
   OpenTelemetry tracing alongside the existing request ids, dashboards and alerts.
5. **Load test** (Gatling/k6) of the Monday spike against a production-like database to confirm the
   numbers in §2 and size the pool, instead of guessing.
6. **Availability read model** (§3) once the load test shows it is needed.
7. **CI pipeline** (build, tests incl. the concurrency suite, image build, Flyway validation) and
   contract tests from the OpenAPI spec.
