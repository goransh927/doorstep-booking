# Design notes

Rough flow of a booking request:

```
POST /bookings
  -> already seen this Idempotency-Key?  return the old booking
  -> validate service, pincode, slot, 2h..7d window
  -> read qualified techs + their bookings for that day (no locks)
  -> sort free techs: fewest bookings that day, then lowest id
  -> for each tech:
        BEGIN
          SELECT ... FROM technician WHERE id = ? FOR UPDATE
          still free?  INSERT booking   (exclusion constraint checks again)
        COMMIT
  -> nobody left: 409 NO_AVAILABILITY
```

## 1. Concurrency

I went with two things together.

First, a row lock on the technician. Each attempt is a small transaction: lock the technician
row, check again that they're free, insert. If two requests want the same person at the same
time, the second one waits a few ms on the lock, then sees the first booking and moves on to the
next technician (or gets a 409). I only ever hold one technician lock at a time, so there's no
deadlock to worry about.

Second, a Postgres exclusion constraint on `(technician_id, tstzrange(start_time, blocked_until))`
for rows that aren't cancelled. `blocked_until` is end time + the 30 min buffer. Even if some
future code forgets to take the lock, the database still won't accept an overlapping booking. A
cancel just changes the status, so the time is free again straight away.

Why not the other options:

- A unique constraint on `(technician_id, start_time)` only stops two bookings with the *same*
  start. A 60 min job at 10:00 and a 30 min job at 10:30 would both go through, and it does
  nothing for the buffer.
- Optimistic versioning works, but when a slot is popular most requests fail and retry the whole
  thing, and that gets worse exactly when traffic is high. Waiting on a lock is cheaper here.
- SERIALIZABLE isolation also works, but you get retries on false conflicts and retry code
  everywhere.
- A distributed lock (Redis etc.) is one more system that can fail, and lease expiry can still
  let two writers in. The database already has a lock, so I used that.

Where it breaks: it assumes one Postgres primary. All bookings for one technician are serialized,
which is fine for a real person with ~10 jobs a day. The ranking is done before locking, so under
a race two requests may both pick the same "least busy" person. One of them moves on, so it's
still correct, but the load balancing is only approximate.

## 2. Scale (50 cities, 5,000 techs, 200k bookings/day, 10x Monday spike)

Quick numbers: 200k/day averages about 2-3 writes/sec. Bunch most of it into daytime hours and
multiply by 10 for Monday morning and it's maybe 50-60 bookings/sec at peak. One Postgres
handles that fine. Reads of availability will be far higher than that, so reads hit limits first.

What I'd change, roughly in this order:

1. Send `/availability` to read replicas. I'd watch p95/p99 latency and DB CPU to know when.
2. Put PgBouncer in front of the DB and run more app instances. With 10x spikes the connection
   pool usually runs out before the database does (watch Hikari pending connections).
3. Cache services/zones/technicians in memory. They barely change and right now each request
   reads them.
4. Partition `booking` by month and archive old rows. 200k/day is ~70M rows a year, and I want
   the overlap index to stay small.
5. Move rate limiting to the gateway or Redis, since right now it's per instance.
6. Shard by city only if one primary really can't keep up. Cities are independent (a tech never
   works in two cities), so it would be a clean split, but I wouldn't do it early.

I'd also keep an eye on: count of 409s by code, lock wait time, and exclusion constraint
violations. That last one should be basically zero; if it isn't, some code path is skipping the
lock.

## 3. Fast availability without showing slots that are gone

Instead of querying bookings every time, I'd keep a small per-technician, per-day bitmap of
30 min cells (24 cells for 08:00-20:00) with the buffer already applied. Availability for a
zone + skill + date becomes OR-ing a few dozen bitmaps, which is very cheap. These can live in
Redis per (zone, date), updated when a booking or cancel commits, and rebuilt from Postgres if
missing. A few seconds of HTTP caching on top helps with refresh storms.

The important part is the cache is never used to decide a booking. The booking path always
checks again under the lock. So the worst case of a stale cache is a customer picks a slot that
just got taken and gets a 409, then the app refreshes the list. I'd track how often that happens
and shorten TTLs if it goes up. If product wants it smoother, a short "hold" (a booking with
status HELD that expires after 2-3 min) would move the failure to when the slot is picked instead
of at confirm.

## 4. Notifications

The SMS provider shouldn't be called inside the booking request at all.

- In the same transaction as the booking insert, write a row to an `outbox_event` table. Then
  either both commit or neither does, so we never notify for a booking that rolled back.
- A worker picks up outbox rows (`FOR UPDATE SKIP LOCKED` so multiple workers can run) and
  pushes them to a queue.
- Separate consumers for SMS/WhatsApp and for technician push, so one being down doesn't block
  the other.
- Each send uses `booking_id + channel` as a dedupe key, so a retry doesn't text the customer
  twice.
- Timeouts, retries with backoff, a circuit breaker around the provider, and a dead-letter queue
  with an alert after N failures. A backup provider if the business cares enough.

The booking API stays fast even if SMS is down for an hour; the backlog just goes out later. I'd
alert on the age of the oldest unsent outbox row.

## 5. Reassigning a sick technician's jobs

The easy part: the assignment is a single column, `technician_id`, and all the rules are in
`SchedulingPolicy`, so finding a replacement is basically the same ranking call with the sick tech
left out. The same lock + constraint protect an UPDATE just as they protect an INSERT, so moving a
job can't double-book the new person.

The hard parts:

- There's no concept of a technician being unavailable. I'd add a
  `technician_unavailability(technician_id, from, to, reason)` table and have `isFree` check it.
- Changing `technician_id` overwrites who was originally assigned. Ops will want history, so I'd
  add an assignment history table.
- Some jobs won't have anyone free. It should be done per booking and report which ones failed, so
  ops can call those customers. Not all-or-nothing.
- Customer and both technicians need to be told, which comes from the outbox above.

## 6. With another week

1. Outbox + notifications, since customers expect a confirmation and it's the first thing that
   fails in real life.
2. Check ownership on cancel (phone + OTP). Right now anyone with the id can cancel.
3. Technician unavailability and reassignment, because sick days will happen on day one.
4. Metrics and tracing (booking results by error code, lock wait, outbox lag) plus dashboards.
5. A load test of the Monday spike with k6 or Gatling, so the numbers in section 2 are measured
   and not guessed.
6. The availability bitmap cache, but only if the load test says it's needed.
7. CI running the full test suite including the concurrency tests.
