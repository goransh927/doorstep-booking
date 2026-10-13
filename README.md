# Doorstep Service Booking API

A booking service for doorstep vehicle-battery jobs. Customers pick a service, a pincode and a
time slot; the service assigns a free, qualified technician and **never double-books anyone**,
including under concurrent requests.

Java 17 · Spring Boot 3.4 · PostgreSQL 16 · Flyway · JdbcClient · JUnit 5

Design answers (Part B) are in [DESIGN.md](DESIGN.md).

---

## Quick start

```bash
docker compose up --build
```

That starts PostgreSQL, applies migrations and seed data, and serves the API on
`http://localhost:8080`.

- Swagger UI: http://localhost:8080/swagger-ui.html (OpenAPI JSON at `/v3/api-docs`)
- Health: http://localhost:8080/actuator/health

### Run the tests

```bash
./mvnw test        # Windows: mvnw.cmd test
```

No Docker needed. The integration tests start a **real PostgreSQL** from embedded binaries
([zonky embedded-postgres](https://github.com/zonkyio/embedded-postgres)), so the exclusion
constraint, row locks and transactions behave exactly as in production. The first run downloads
dependencies and takes about a minute.

Run just the concurrency test (good for a live demo):

```bash
./mvnw test -Dtest=ConcurrentBookingIntegrationTest
```

### Run the app against your own PostgreSQL

```bash
DB_URL=jdbc:postgresql://localhost:5432/booking DB_USERNAME=booking DB_PASSWORD=booking ./mvnw spring-boot:run
```

The database user needs permission to `CREATE EXTENSION btree_gist` (any superuser, or the DB owner
on PostgreSQL 13+, where btree_gist is a trusted extension).

---

## API

All times are IST. Responses render timestamps as ISO-8601 with `+05:30`. Requests may send an
offset (`2030-01-08T10:00:00+05:30`) or a bare local time (`2030-01-08T10:00`), which is read as IST.

| # | Endpoint | Success | Notes |
|---|----------|---------|-------|
| 1 | `GET /availability?serviceId=&pincode=&date=` | 200 | Bookable starts for the day, with how many technicians are free for each |
| 2 | `POST /bookings` | 201 | Optional `Idempotency-Key` header |
| 3 | `GET /bookings/{id}` | 200 | |
| 4 | `POST /bookings/{id}/cancel` | 200 | Cancelling a cancelled booking is a no-op that returns it |
| 5 | `GET /technicians/{id}/schedule?date=` | 200 | Live (non-cancelled) bookings starting that day, in start order |
| + | `GET /bookings?phone=&status=&page=&size=` | 200 | A customer's bookings, newest start first. `size` ≤ 100 |
| + | `GET /actuator/health` | 200 | Includes a DB check |

### Examples

```bash
# 1. What can I book tomorrow for a jump-start in 560037?
curl "http://localhost:8080/availability?serviceId=2&pincode=560037&date=2030-01-08"
```
```json
{
  "service": { "id": 2, "name": "Jump-start", "durationMinutes": 30 },
  "pincode": "560037",
  "date": "2030-01-08",
  "slots": [
    { "startTime": "2030-01-08T08:00:00+05:30", "endTime": "2030-01-08T08:30:00+05:30", "availableTechnicians": 1 },
    { "startTime": "2030-01-08T08:30:00+05:30", "endTime": "2030-01-08T09:00:00+05:30", "availableTechnicians": 1 }
  ]
}
```

```bash
# 2. Book it (safe to retry with the same key)
curl -i -X POST http://localhost:8080/bookings \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 7f9c2ba4-e88f-4b1f-9d1e-3c2b0e4f5a61" \
  -d '{"serviceId":2,"pincode":"560037","startTime":"2030-01-08T10:00:00+05:30","customerPhone":"+919876543210"}'
```
```
HTTP/1.1 201
Location: /bookings/1
Idempotent-Replayed: false
X-Request-Id: 4b0f8a7e-...
```
```json
{
  "id": 1,
  "status": "CONFIRMED",
  "customerPhone": "+919876543210",
  "service": { "id": 2, "name": "Jump-start", "durationMinutes": 30 },
  "pincode": "560037",
  "startTime": "2030-01-08T10:00:00+05:30",
  "endTime": "2030-01-08T10:30:00+05:30",
  "technician": { "id": 5, "name": "Kiran Rao" },
  "createdAt": "2030-01-07T06:00:00+05:30"
}
```

```bash
# 3-5
curl http://localhost:8080/bookings/1
curl -X POST http://localhost:8080/bookings/1/cancel
curl "http://localhost:8080/technicians/5/schedule?date=2030-01-08"

# Customer history (URL-encode the +)
curl "http://localhost:8080/bookings?phone=%2B919876543210&page=0&size=20"
```

(Dates above are examples; pick one between 2 hours and 7 days from when you run it.)

### Errors

Every error has the same body:

```json
{
  "timestamp": "2030-01-07T00:30:00Z",
  "status": 409,
  "code": "NO_AVAILABILITY",
  "message": "No qualified technician is free for this slot",
  "path": "/bookings",
  "requestId": "4b0f8a7e-...",
  "details": [ { "field": "pincode", "message": "must be a 6-digit pincode" } ]
}
```

Clients should branch on `code`; `details` appears only for validation errors.

| HTTP | `code` | When |
|------|--------|------|
| 400 | `VALIDATION_ERROR` | Missing/malformed field, parameter or JSON; bad phone/pincode format |
| 404 | `NOT_FOUND` | Unknown booking, technician or endpoint |
| 409 | `NO_AVAILABILITY` | No qualified technician is free for that slot (taken, outside shifts, or lost a race) |
| 409 | `CANCELLATION_WINDOW_CLOSED` | Cancel attempted less than 1 hour before start |
| 409 | `BOOKING_NOT_CANCELLABLE` | Booking is already `COMPLETED` |
| 422 | `UNKNOWN_SERVICE` | `serviceId` does not exist |
| 422 | `UNSERVICEABLE_PINCODE` | Pincode is not in any zone |
| 422 | `SLOT_NOT_ALIGNED` | Start is not on a :00/:30 boundary |
| 422 | `OUTSIDE_BOOKING_WINDOW` | Start < now + 2h, or end > now + 7d |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same `Idempotency-Key` sent with a different body |
| 429 | `RATE_LIMITED` | Too many `POST /bookings` from one client IP (with `Retry-After`) |
| 500 | `INTERNAL_ERROR` | Bug; details are logged with the request id, not returned |

Every response carries `X-Request-Id` (yours if you send a sane one, otherwise generated); it is
in every log line and in error bodies.

---

## How double-booking is prevented (short version)

1. Rank free, qualified technicians from a lock-free read (fewest bookings that day first).
2. For each candidate, in its own short transaction: `SELECT … FOR UPDATE` the technician row,
   re-check for overlap (including buffer), insert. Requests for the same technician queue on that
   lock; the loser falls through to the next candidate or gets `409 NO_AVAILABILITY`.
3. Backstop: a PostgreSQL **exclusion constraint** on
   `(technician_id, tstzrange(start_time, blocked_until))` for non-cancelled rows makes an overlap
   impossible to commit, whatever the code does.

Details, trade-offs and where it breaks: [DESIGN.md](DESIGN.md#1-concurrency-choice).

---

## Assumptions and decisions

- **Time zone.** Everything is IST (`Asia/Kolkata`). Dates in query params are IST calendar days.
- **Slots.** Start on :00 or :30. Availability lists every aligned start inside the window where
  at least one qualified technician is free.
- **Booking window.** The whole booking must fit: `start ≥ now + 2h` and `end ≤ now + 7d`.
  Exactly on the boundary is allowed.
- **Shift.** The job itself must fit in `[shiftStart, shiftEnd]` (a 60-min job can start at 19:00
  with a 20:00 shift end). The 30-minute travel buffer after the *last* job may run past shift end
  (it is travel home, not work). Shifts do not cross midnight.
- **Buffer.** A booking blocks `[start, end + 30 min)`. Two bookings of one technician conflict
  if those ranges overlap, so a 30-min gap is required *between* jobs in both directions. The
  buffer is stored per booking (`blocked_until`), so changing the policy later does not
  retroactively alter existing bookings.
- **Tie-break.** Among free technicians with the same number of bookings that day, the lowest
  technician id wins. Deterministic, easy to test and to explain to operations. "Bookings that
  day" counts non-cancelled bookings starting that IST day.
- **Load counts are a heuristic, not an invariant.** Ranking uses a snapshot; under concurrency two
  requests may both see the same technician as least-loaded. Correctness (no overlap) is still
  guaranteed; only the fairness may be off by one booking.
- **Idempotency.** Keys are global (not per phone), up to 255 chars, kept forever (in the booking
  row). A replay returns the original booking with `201` and `Idempotent-Replayed: true`, even if
  it has since been cancelled. A failed attempt (e.g. 409) stores nothing, so retrying the same key
  later re-attempts the booking. Requests without a key are never deduplicated.
- **Cancellation.** Allowed while `now ≤ start - 1h`. Cancelling twice returns the cancelled
  booking (idempotent). `COMPLETED` cannot be cancelled. There is no endpoint to complete a job;
  the status exists in the schema for the field app.
- **Identity.** No auth, per the brief. Anyone with a booking id can cancel it; in production
  cancel would require the customer's phone/OTP.
- **Unknown references in input** (service, pincode) are `422` rather than `404`, since the URL
  itself exists; unknown path ids (`/bookings/{id}`) are `404`.
- **Phone format.** 10–15 digits with an optional leading `+`; stored as sent (no normalisation).
- **Rate limit.** 20 `POST /bookings` burst per client IP, refilling 20/min, in memory, per
  instance. Behind a proxy you would key on a trusted `X-Forwarded-For`.

## Seed data

Loaded by Flyway (`src/main/resources/db/migration/V2__seed_reference_data.sql`).

| Service | Duration | Skill |
|---------|----------|-------|
| 1 Battery replacement | 60 | BATTERY_FIT |
| 2 Jump-start | 30 | JUMPSTART |
| 3 Battery health check | 30 | DIAGNOSTICS |

| Zone | Pincodes | Technicians (skills), all 08:00–20:00 |
|------|----------|---------------------------------------|
| 1 Bengaluru South | 560034, 560068, 560076 | 1 Ravi (FIT, DIAG), 2 Suresh (JUMP, DIAG), 3 Anil (FIT, JUMP, DIAG) |
| 2 Bengaluru East | 560037, 560066, 560103 | 4 Priya (FIT), 5 Kiran (JUMP, DIAG), 6 Meena (FIT, DIAG) |

Kiran is the only jump-starter in zone East, which makes the "last free technician" race easy to
reproduce by hand: fire two `POST /bookings` for service 2 at pincode 560037 at the same time.

## Project layout

```
src/main/java/com/amarraja/booking
├── domain/        Pure model + SchedulingPolicy (every rule; no I/O, no clock)
├── persistence/   JdbcClient repositories, SQL is explicit; Postgres error classification
├── service/       Use cases: availability, booking (locking + idempotency), schedule
├── web/           Controllers, DTOs, error handler, request-id filter, rate limiter
└── config/        Typed properties (booking.*), Clock, policy wiring
src/main/resources/db/migration   V1 schema, V2 seed data
src/test/java/...                 Unit tests (policy, rate limiter), API + concurrency integration tests
```

Rules are configurable in `application.yml` under `booking.*` (buffer, lead time, horizon, etc.).

## Tests

| Test | What it proves |
|------|----------------|
| `SchedulingPolicyTest` | Overlap and buffer in both directions, shift edges, skill and zone checks, ranking and tie-break, booking window and cancellation boundaries, slot alignment |
| `BookingApiIntegrationTest` | Full HTTP lifecycle (availability → book → get → schedule → cancel → slot freed), assignment order, idempotent replay and key misuse, every error code, pagination, request ids, health |
| `ConcurrentBookingIntegrationTest` | 20 threads race for the last technician: exactly one 201, nineteen 409s, one row. 20 threads vs 2 technicians: each technician booked exactly once. 20 concurrent retries with one idempotency key: one booking. Direct SQL insert of an overlap is rejected by the database (SQLSTATE 23P01) |

## What I cut, and why

- **Auth / ownership checks on cancel**: out of scope per the brief.
- **Admin APIs** for services, zones, technicians: not required; seed data is a migration.
- **Booking completion endpoint**: no requirement drives it; the status is modelled.
- **Outbox / notifications**: designed in DESIGN.md, not built, to keep the core small and correct.
- **Distributed rate limiting**: per-instance is enough to show the behaviour; Redis or the API
  gateway would own this in production.
- **Docker build does not run tests**: the embedded PostgreSQL used by tests will not run as root
  in a container. Tests run on the host (and would in CI).

## AI assistance

Built with Claude Code (Anthropic) as a pair: scaffolding, first drafts of tests and docs, and
review. Design choices (lock + exclusion constraint, idempotency in the booking row, rule
placement) were reviewed and I can walk through and change every line.
