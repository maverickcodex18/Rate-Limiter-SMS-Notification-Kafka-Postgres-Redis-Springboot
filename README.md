# RateLimitedSMSGateway

A concurrency-safe, rate-limited SMS notification gateway built with **Spring Boot 3.5 (Java 21)**, **Kafka**, **PostgreSQL**, and **Redis**.

Exposes a REST API for configuring per-user rate limits and sending SMS notifications. Rate limiting uses a lazy refresh pattern — counters reset inline when the time window elapses, no background jobs needed.

## Architecture

```
Client
  │
  ├─ POST /api/config ──► CreateUserConfig ──► Postgres (upsert, reset counter)
  │                                         └─► Redis (invalidate cache)
  │
  └─ POST /api/send ──► SendMessage ──► Redis (check + increment counter)
                                     │   └─ Cache miss → fetch from Postgres
                                     │
                                     ├─► Kafka (publish to sms-outbound)
                                     └─► KafkaListener (consume + log "SMS Sent")
```

**Phase 1** — Postgres-backed rate limiting with `SELECT ... FOR UPDATE` row-level locking.
**Phase 2** — Redis caching layer for configs + counter operations. Postgres remains source of truth. Auto-falls back to Phase 1 when Redis is down.

## Tech Stack

| Component | Technology |
|-----------|-----------|
| API | Spring Boot 3.5, Java 21, Virtual Threads |
| Database | PostgreSQL 15 |
| Cache | Redis 8 |
| Messaging | Apache Kafka (Confluent 7.5) |
| Containers | Docker, Docker Compose |

## Quick Start

```bash
# 1. Clone
git clone <repo-url>
cd RateLimitedSMSGateway/RateLimitedSMSGateway

# 2. Create your env file from the template
cp .env.template .env
```

Edit `.env` and set `BASE_DIR` to an absolute path on your machine:

```env
# Windows
BASE_DIR=C:/Projects/RateLimitedSMSGateway/docker_data

# Linux / macOS
BASE_DIR=/home/youruser/RateLimitedSMSGateway/docker_data
```

```bash
# 3. Start everything
docker compose up --build
```

This spins up PostgreSQL, Redis, Zookeeper, Kafka, and the Gateway API on port **8080**.

## API Reference

### POST `/api/config`

Configure or update a user's rate limit. Resets counter to 0 on every call.

```json
{ "userId": 1001, "rateLimit": 5, "timeWindow": 1 }
```

| Field | Type | Validation | Description |
|-------|------|-----------|-------------|
| userId | int | >= 0 | Unique user identifier |
| rateLimit | int | >= 1 | Max messages per window |
| timeWindow | int | >= 1 | Window duration in minutes |

| Status | Meaning |
|--------|---------|
| 200 | Config saved (returns persisted entity with currentCount=0) |
| 400 | Missing/invalid fields |
| 503 | Postgres unreachable |

### POST `/api/send`

Send a rate-limited notification. Publishes to Kafka `sms-outbound` topic on success.

```json
{ "userId": 1001, "message": "Hello!" }
```

| Field | Type | Validation | Description |
|-------|------|-----------|-------------|
| userId | int | >= 0 | Target user |
| message | String | not blank | Message content |

| Status | Meaning |
|--------|---------|
| 200 | Message sent and published to Kafka |
| 400 | Missing/invalid fields |
| 404 | No config exists for this userId |
| 429 | Rate limit exceeded |
| 503 | Kafka or Postgres unreachable |

## Stopping

```bash
docker compose down        # stop containers
docker compose down -v     # stop + remove volumes
```




# Design Document: Rate-Limited Notification Gateway

## Overview

A concurrency-safe, rate-limited SMS notification gateway built with Spring Boot (Java 21), Kafka, Postgres, and Redis. The system exposes a REST API for configuring per-user rate limits and sending SMS notifications. Rate limiting uses a single Postgres table (`PostgresEntity`) with a lazy refresh pattern — counters reset inline on the send path when the time window elapses, with no background jobs.

The architecture follows a phased approach:
- **Phase 1**: Postgres-backed rate limiting with `SELECT ... FOR UPDATE` row-level locking, Kafka event publishing to `sms-outbound`, a Sender Service consumer (in-process `@KafkaListener`), and Docker Compose orchestration.
- **Phase 2**: Adds a Redis caching layer using `@RedisHash("RateLimiterCache")` with `CrudRepository`. Redis stores a single Hash per user at key `RateLimiterCache:{userId}` containing `rateLimit`, `currentCount`, and a `@TimeToLive` TTL (in seconds). On cache miss, the system queries Postgres and populates Redis. Postgres remains the source of truth. Atomic Lua script operations are deferred to future scope.

Key design decisions:
- Single table combining config and counters avoids joins and simplifies the transactional boundary.
- Lazy refresh eliminates background schedulers and clock-drift issues.
- Phase 1: `current_count` is incremented only after Kafka publish confirmation, preventing phantom counts.
- Phase 2: Redis increments counter BEFORE Kafka publish — deliberate over-count tradeoff (safety over accuracy).
- Java 21 Virtual Threads (`spring.threads.virtual.enabled=true`) for high-throughput I/O-bound request handling.
- Java Records for all DTOs (nested inside `AllRecords` class).
- `userId` is `int` throughout the entire system (entity, DTOs, repository, endpoints).
- No interfaces — concrete `@Component` classes with constructor injection.
- `@RestControllerAdvice` (`GlobalExceptionRepository`) handles all exceptions; no try-catch in service layer.

## Dependencies

### Gateway API (Spring Initializr)

| Dependency | Purpose |
|------------|---------|
| Spring Web | REST endpoints (`/api/config`, `/api/send`) |
| Spring Data JPA | Postgres access, `@Lock(PESSIMISTIC_WRITE)`, repositories |
| PostgreSQL Driver | JDBC driver for Postgres |
| Spring for Apache Kafka | Kafka producer + in-process consumer for `sms-outbound` topic |
| Validation | `@Valid`, `@Min`, `@NotBlank` on request DTOs |
| Lombok | `@Data`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Getter` |
| Spring Data Redis | `@RedisHash`, `CrudRepository`, `@TimeToLive` for Redis caching |

### Manual Additions (pom.xml — test scope, currently commented out)

| Dependency | Coordinates | Purpose |
|------------|-------------|---------|
| jqwik | `net.jqwik:jqwik:1.9.2` | Property-based testing |
| TestContainers PostgreSQL | `org.testcontainers:postgresql` | Postgres container for integration tests |
| TestContainers Kafka | `org.testcontainers:kafka` | Kafka container for integration tests |
| TestContainers JUnit 5 | `org.testcontainers:junit-jupiter` | JUnit 5 integration for TestContainers |
| TestContainers Redis | `org.testcontainers:redis` | Redis container for Phase 2 integration tests |

### Project Settings

- Java version: **21**
- Spring Boot: **3.2+** (latest 3.x for Virtual Threads support)
- `spring.threads.virtual.enabled=true` in `application.properties`
- `spring.jpa.hibernate.ddl-auto=update` (auto-creates table from entity)

## Architecture

### System Architecture (Phase 1)

```mermaid
graph TD
    Client

    subgraph GatewayAPI["Gateway API - Spring Boot"]
        ConfigCtrl["CreateUserConfig - POST /api/config"]
        SendCtrl["SendMessage - POST /api/send"]
        RL["SendMessagePostgresService - Lazy refresh + SELECT FOR UPDATE"]
    end

    Client -->|POST /api/config| ConfigCtrl
    Client -->|POST /api/send| SendCtrl

    ConfigCtrl -->|UpsertDB.insertUserConfigToDB| PG[(Postgres - PostgresEntity table)]

    SendCtrl --> RL
    RL -->|findByUserIdForUpdate - SELECT FOR UPDATE| PG
    RL -->|If allowed: KafkaConfig.sendToKafka| K[Kafka - sms-outbound]
    RL -->|After Kafka ack: updateCurrentRateCountPostgres| PG

    K -->|KafkaListener in KafkaConfig| Console[Console - SMS Sent log]
```

### System Architecture (Phase 2)

```mermaid
graph LR
    Client

    subgraph GatewayAPI["Gateway API - Spring Boot"]
        ConfigCtrl2["CreateUserConfig - POST /api/config"]
        SendCtrl2["SendMessage - POST /api/send"]
        RL2["SendMessageRedisService - Redis-first with Postgres fallback"]
    end

    subgraph Infra["Infrastructure"]
        R2[("Redis - RateLimiterCache Hash")]
        PG2[("Postgres - PostgresEntity table")]
        K2["Kafka - sms-outbound"]
    end

    Client -->|POST /api/config| ConfigCtrl2
    Client -->|POST /api/send| SendCtrl2

    ConfigCtrl2 -->|UpsertDB.insertUserConfigToDB| PG2
    ConfigCtrl2 -->|RedisService.deleteUser - invalidate cache| R2

    SendCtrl2 --> RL2
    RL2 -->|RedisService.queryRedis - cache hit| R2
    RL2 -->|Cache miss: fetchUserFromPostgresWithoutLock| PG2
    RL2 -->|RedisService.incrementCurrentCount - delete+save| R2
    RL2 -->|If Redis down: sendMessagePostgres fallback| PG2
    RL2 -->|KafkaConfig.sendToKafka| K2
    RL2 -.->|updateCurrentCountWithoutDBHit - sync count| PG2

    K2 -->|KafkaListener| Console[Console - SMS Sent log]
```

### Send Path Flow (Phase 1 — Postgres with Lazy Refresh)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as SendMessage Controller
    participant P as SendMessagePostgresService
    participant DB as Postgres
    participant K as KafkaConfig

    C->>S: POST /api/send {userId, message}
    S->>P: sendMessagePostgres(userId, message)
    P->>DB: BEGIN + findByUserIdForUpdate(userId) - SELECT FOR UPDATE
    alt No row found
        P-->>S: throw UserNotFoundException (404)
    else Row found
        Note over P: checkRateLimitAndUpdateRefreshTimePostgres
        alt Window elapsed (diffMinutes >= timeWindow)
            P->>DB: Reset currentCount=0, lastRefreshTime=now, save()
        else Within window, currentCount >= rateLimit
            P-->>S: throw RateLimitExceededException (429)
        end
        P->>K: sendToKafka(userId, message)
        alt Kafka ack success
            P->>DB: updateCurrentRateCountPostgres (currentCount+1, save)
            P->>DB: COMMIT
            S-->>C: 200 Message Sent Successfully
        else Kafka failure
            P-->>S: throw KafkaPublishException (503)
            Note over DB: Transaction rolls back
        end
    end
```

### Send Path Flow (Phase 2 — Redis Hot Path)

```mermaid
sequenceDiagram
    participant C as Client
    participant S as SendMessage Controller
    participant R as SendMessageRedisService
    participant RS as RedisService
    participant PS as SendMessagePostgresService
    participant DB as Postgres
    participant K as KafkaConfig

    C->>S: POST /api/send {userId, message}
    S->>R: sendMessageRedis(userId, message)
    R->>RS: queryRedis(userId)
    alt Cache miss (null)
        R->>PS: fetchUserFromPostgresWithoutLock(userId)
        alt No row
            PS-->>R: throw UserNotFoundException (404)
        else Found
            Note over R: Calculate TTL and currentCount
            alt Window expired (diffMinutes >= timeWindow)
                R->>PS: updateLastRefreshRateAndCurrentCount
                Note over R: ttl = timeWindow * 60s, currentCount = 0
            else Window active
                Note over R: ttl = (timeWindow - diffMinutes) * 60s
                Note over R: currentCount = postgresEntity.getCurrentCount()
            end
            R->>RS: insertToRedis(new RedisEntity)
        end
    end
    R->>R: checkRateLimitRedis(redisEntity)
    alt rateLimit <= currentCount
        R-->>S: throw RateLimitExceededException (429)
    end
    R->>RS: incrementCurrentCount (delete + save, currentCount+1)
    R->>K: sendToKafka(userId, message)
    alt Kafka failure
        R-->>S: throw KafkaPublishException (503)
        Note over R: Counter already incremented in Redis (over-count tradeoff)
    end
    R->>PS: updateCurrentCountWithoutDBHit(userId, currentCount)
    S-->>C: 200 Message Sent Successfully
```
## System Flow on Redis Failure

When Redis becomes unavailable (crash, network partition, timeout), the system degrades gracefully without dropping requests or bypassing rate limits. The fallback path reuses the Phase 1 Postgres-backed logic entirely.

### Detection

- The `SendMessage` controller wraps the `sendMessageRedis()` call in a try-catch for Redis exceptions (`RedisConnectionFailureException`, `RedisCommandTimeoutException`, or any `DataAccessException` from Redis operations).
- On any Redis exception, the controller logs a `WARN`-level message and delegates immediately to `sendMessagePostgres()`.
- No circuit breaker is needed initially — the try-catch fallback is sufficient. A circuit breaker (e.g., Resilience4j) can be added later if Redis flapping causes latency spikes from repeated connection attempts.

### Fallback Flow

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway API
    participant R as Redis (DOWN)
    participant P as Postgres
    participant K as Kafka

    C->>G: POST /api/send {userId, message}
    G->>R: queryRedis(userId)
    R--xG: Connection refused / timeout
    Note over G: Redis exception caught — fallback to Postgres path
    G->>P: BEGIN
    G->>P: SELECT * FROM rate_limit_store WHERE user_id = ? FOR UPDATE
    alt Row found, within limit
        G->>P: checkRateLimitAndUpdateRefreshTimePostgres()
        G->>K: sendToKafka(userId, message)
        K-->>G: Ack
        G->>P: UPDATE currentCount = currentCount + 1
        G->>P: COMMIT
        G-->>C: 200 OK
    else Rate limited
        G->>P: ROLLBACK
        G-->>C: 429 Too Many Requests
    end
```

### Behavior During Redis Outage

| Aspect | Behavior |
|--------|----------|
| Config cache | Bypassed — config fetched directly from Postgres via `findByUserIdForUpdate` |
| Rate limit check | Uses `SELECT ... FOR UPDATE` (Phase 1 path) with lazy refresh |
| Counter increment | Inside Postgres transaction, after Kafka ack |
| Throughput | Reduced for same-user bursts (Postgres row lock serializes requests) |
| Correctness | Fully preserved — no requests bypass the limit |
| User experience | Slightly higher latency for same-user bursts; different-user requests unaffected |

### Recovery

When Redis comes back online:
- The next `sendMessageRedis()` call succeeds, and the hot path resumes automatically.
- Redis keys may have expired during the outage, so the first request triggers a cache miss → Postgres fetch → Redis populate cycle. This self-heals without manual intervention.
- The async Postgres sync (`updateCurrentCountWithoutDBHit`) continues to keep the source of truth accurate regardless of Redis state.

### Config Update During Redis Outage

If `/api/config` is called while Redis is down:
- Postgres upsert succeeds normally via `UpsertDB.insertUserConfigToDB()`.
- `RedisService.deleteUser(userId)` fails silently (logged as `WARN`).
- The stale cache entry expires naturally via TTL, or is absent entirely if Redis restarts clean.
- No data inconsistency risk — Postgres is always the source of truth.


## Components (Actual Codebase)

### Package Structure

```
com.example.RateLimitedSMSGateway/
├── Endpoints/
│   ├── CreateUserConfig.java      — POST /api/config controller
│   └── SendMessage.java           — POST /api/send controller
├── Entity/
│   ├── PostgresEntity.java        — JPA entity (@Entity, @Table)
│   └── RedisEntity.java           — Redis entity (@RedisHash)
├── Repository/
│   ├── PostgresRepository.java    — JpaRepository with @Lock and @Modifying
│   └── RedisRepository.java       — CrudRepository<RedisEntity, Integer>
├── Services/
│   ├── UpsertDB.java              — Config upsert logic (insert or update)
│   ├── SendMessagePostgresService.java — Phase 1 send path (SELECT FOR UPDATE)
│   ├── SendMessageRedisService.java    — Phase 2 send path (Redis-first)
│   ├── RedisService.java          — Redis CRUD operations
│   └── KafkaConfig.java           — Kafka producer + @KafkaListener consumer
├── CustomExceptions/
│   ├── CustomExceptionTemplate.java    — Base exception (message + statusCode)
│   ├── UserNotFoundException.java      — 404
│   ├── RateLimitExceededException.java — 429
│   ├── KafkaPublishException.java      — 503
│   └── GlobalExceptionRepository.java  — @RestControllerAdvice handler
└── DTO/
    └── AllRecords.java            — Nested Java Records for all DTOs
```

### REST Endpoints

**POST `/api/config`** — `CreateUserConfig` controller

Request body (JSON):
```json
{
  "userId": 101,
  "rateLimit": 10,
  "timeWindow": 5
}
```

| Status | Body | Condition |
|--------|------|-----------|
| 200 | `RespondCreateUserConfig` record | Success (insert or update) |
| 400 | `"Missing/Invalid Arguments"` | `@Valid` fails (`@Min` on userId/rateLimit/timeWindow) |
| 503 | `"Postgres Down : Service Unavailable"` | `DataAccessException` caught by `GlobalExceptionRepository` |

Behavior:
1. `UpsertDB.insertUserConfigToDB(userId, rateLimit, timeWindow)` — upserts Postgres row with `currentCount=0`, `lastRefreshTime=now()`
2. `RedisService.deleteUser(userId)` — invalidates Redis cache (wrapped in try-catch; if Redis down, logs and continues)
3. Returns `RespondCreateUserConfig` record with all fields

**POST `/api/send`** — `SendMessage` controller

Request body (JSON):
```json
{
  "userId": 101,
  "message": "Hello World"
}
```

| Status | Body | Condition |
|--------|------|-----------|
| 200 | `"Message Sent Successfully"` | Allowed and published to Kafka |
| 400 | `"Missing/Invalid Arguments"` | `@Valid` fails |
| 404 | `"User Not Found"` | No config for userId |
| 429 | `"Rate Limit Exceeded"` | Rate limit reached |
| 503 | `"Kafka Down : Service Unavailable"` | Kafka unreachable |

Behavior:
1. Try `SendMessageRedisService.sendMessageRedis(userId, message)` (Phase 2 Redis path)
2. Catch `RedisConnectionFailureException | RedisCommandTimeoutException | RedisSystemException` → log "REDIS DOWN — Falling back to Postgres" → call `SendMessagePostgresService.sendMessagePostgres(userId, message)` (Phase 1 path)
3. All other exceptions propagate to `GlobalExceptionRepository`

### Data Models

#### PostgresEntity (JPA Entity)

```java
@Data @Entity @NoArgsConstructor
@Table(name = "PostgresEntity")
public class PostgresEntity {
    @Id @Column(name = "userId")
    private int userId;

    @Column(name = "rateLimit", nullable = false)
    private int rateLimit;

    @Column(name = "timeWindow", nullable = false)
    private int timeWindow;

    @Column(name = "currentCount", nullable = false)
    private int currentCount;

    @Column(name = "lastRefreshTime", nullable = false)
    private Instant lastRefreshTime;

    public PostgresEntity(int userId, int rateLimit, int timeWindow) {
        this.userId = userId;
        this.rateLimit = rateLimit;
        this.timeWindow = timeWindow;
        this.currentCount = 0;
        this.lastRefreshTime = Instant.now();
    }
}
```

- Table name: `PostgresEntity` (not snake_case)
- Column names: camelCase (`userId`, `rateLimit`, `timeWindow`, `currentCount`, `lastRefreshTime`)
- `userId` is `int` (not String)
- `ddl-auto=update` auto-creates the table from the entity

#### RedisEntity (@RedisHash)

```java
@Data @AllArgsConstructor @NoArgsConstructor
@RedisHash(value = "RateLimiterCache")
public class RedisEntity {
    @Id @Indexed
    private int userId;

    private int rateLimit;
    private int currentCount;

    @TimeToLive
    private long ttl;  // in seconds
}
```

- Redis key pattern: `RateLimiterCache:{userId}`
- `@TimeToLive` field `ttl` is in seconds — Redis auto-deletes the Hash when TTL expires
- TTL calculation: window expired → `timeWindow * 60`; window active → `(timeWindow - diffMinutes) * 60`

### Repositories

#### PostgresRepository

```java
@Repository
public interface PostgresRepository extends JpaRepository<PostgresEntity, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PostgresEntity r WHERE r.userId = :userId")
    Optional<PostgresEntity> findByUserIdForUpdate(@Param("userId") int userId);

    @Modifying
    @Query("UPDATE PostgresEntity r SET r.currentCount=:currentCount WHERE r.userId=:userId")
    void updateCurrentCountWithoutDBHit(@Param("userId") int userId, @Param("currentCount") int currentCount);
}
```

- `findByUserIdForUpdate` — Phase 1 send path, acquires row-level lock
- `updateCurrentCountWithoutDBHit` — Phase 2 Postgres sync, direct JPQL UPDATE without SELECT

#### RedisRepository

```java
@Repository
public interface RedisRepository extends CrudRepository<RedisEntity, Integer> {}
```

### Service Layer

#### UpsertDB — Config persistence

- `insertUserConfigToDB(int userId, int rateLimit, int timeWindow)` → `PostgresEntity`
- Checks `findById(userId)`: if present, updates fields and resets `currentCount=0`, `lastRefreshTime=now()`; if absent, creates new entity via custom constructor
- Exceptions propagate to `GlobalExceptionRepository` (`DataAccessException` → 503)

#### SendMessagePostgresService — Phase 1 send path

- `sendMessagePostgres(int userId, String message)` — `@Transactional`, uses `findByUserIdForUpdate` (SELECT FOR UPDATE)
  1. Fetch row with lock → `UserNotFoundException` if not found
  2. `checkRateLimitAndUpdateRefreshTimePostgres`: lazy refresh check
     - If `diffMinutes >= timeWindow` → reset `currentCount=0`, `lastRefreshTime=now()`, save
     - Else if `currentCount >= rateLimit` → throw `RateLimitExceededException`
  3. `KafkaConfig.sendToKafka(userId, message)` → `KafkaPublishException` on failure
  4. `updateCurrentRateCountPostgres` → `currentCount+1`, save (only after Kafka ack)
- `fetchUserFromPostgresWithoutLock(int userId)` — `findById` (no lock), throws `UserNotFoundException` if not found. Used by Redis path for cache miss.
- `updateLastRefreshRateAndCurrentCount(PostgresEntity, Instant)` — resets `lastRefreshTime` and `currentCount=0`, save. Used by Redis path when window expired on cache miss.
- `updateCurrentCountWithoutDBHit(int userId, int currentCount)` — `@Transactional`, calls `PostgresRepository.updateCurrentCountWithoutDBHit`. Direct JPQL UPDATE, no SELECT. Used by Redis path for Postgres sync.

#### SendMessageRedisService — Phase 2 send path

- `sendMessageRedis(int userId, String message)`:
  1. `RedisService.queryRedis(userId)` — cache hit returns `RedisEntity`, miss returns `null`
  2. On cache miss:
     - `fetchUserFromPostgresWithoutLock(userId)` — get Postgres row (no lock)
     - Calculate `diffMinutes` between `lastRefreshTime` and now
     - If `diffMinutes >= timeWindow`: reset Postgres (`updateLastRefreshRateAndCurrentCount`), `ttl = timeWindow * 60`, `currentCount = 0`
     - If `diffMinutes < timeWindow`: `ttl = (timeWindow - diffMinutes) * 60`, `currentCount = postgresEntity.getCurrentCount()`
     - `RedisService.insertToRedis(new RedisEntity(userId, rateLimit, currentCount, ttl))`
  3. `checkRateLimitRedis(redisEntity)` — if `rateLimit <= currentCount`, throw `RateLimitExceededException`
  4. `RedisService.incrementCurrentCount(redisEntity)` — delete + save with `currentCount+1` (NOT atomic)
  5. `KafkaConfig.sendToKafka(userId, message)` — counter already incremented (over-count tradeoff)
  6. `updateCurrentCountWithoutDBHit(userId, redisEntity.getCurrentCount())` — sync to Postgres

#### RedisService — Redis CRUD operations

- `insertToRedis(RedisEntity)` → `CrudRepository.save()`
- `queryRedis(int userId)` → `CrudRepository.findById()`, returns `null` on miss
- `incrementCurrentCount(RedisEntity)` → set `currentCount+1`, `deleteById`, `save` (not atomic — race condition possible under high concurrency)
- `deleteUser(int userId)` → `CrudRepository.deleteById()`

#### KafkaConfig — Kafka producer + consumer

- `sendToKafka(int userId, String message)` — synchronous send via `kafkaTemplate.send(...).get()`, throws `KafkaPublishException` on failure
- `@KafkaListener(id = "myId", topics = "sms-outbound")` — in-process consumer, logs `"SMS Sent: " + message`
- `@Bean NewTopic topic()` — creates `sms-outbound` topic with 4 partitions, 1 replica

### Custom Exception Hierarchy

```
RuntimeException
└── CustomExceptionTemplate (message: String, statusCode: int)
    ├── UserNotFoundException       → 404 "User Not Found"
    ├── RateLimitExceededException  → 429 "Rate Limit Exceeded"
    └── KafkaPublishException       → 503 "Kafka Down : Service Unavailable"
```

### GlobalExceptionRepository (@RestControllerAdvice)

| Exception | HTTP Status | Response Body |
|-----------|-------------|---------------|
| `CustomExceptionTemplate` | `exc.getStatusCode()` | `exc.getMessage()` |
| `DataAccessException` | 503 | `"Postgres Down : Service Unavailable"` |
| `MethodArgumentNotValidException` | 400 | `"Missing/Invalid Arguments"` |
| `Exception` (catch-all) | 500 | `"Internal Server Error"` |

### DTOs (Java Records in AllRecords.java)

```java
public class AllRecords {
    public record RateLimitConfigRequest(
        @Min(0) int userId,
        @Min(1) int rateLimit,
        @Min(1) int timeWindow
    ) {}

    public record RespondCreateUserConfig(
        int userId, int rateLimit, int timeWindow,
        int currentCount, Instant lastRefreshTime
    ) {}

    public record SendRequest(
        @Min(0) int userId,
        @NotBlank String message
    ) {}
}
```

### Kafka Message Format

Topic: `sms-outbound`, 4 partitions, 1 replica

Key: not explicitly set (default partitioning)

Value: `"{userId}: {message}"` (plain string, not JSON)

### Docker Compose Services

| Service | Image | Port | Health Check |
|---------|-------|------|-------------|
| app (Gateway API) | Built from Dockerfile | 8080 | depends_on kafka, postgres, redis |
| zookeeper | confluentinc/cp-zookeeper:7.5.0 | 2181 | — |
| kafka | confluentinc/cp-kafka:7.5.0 | 9092, 29092 | `nc -z localhost 9092` |
| postgres | postgres:15-alpine | 5432 | `pg_isready` |
| redis | redis:8.0.6-alpine3.21 | 6379 | `redis-cli ping` |

Network: `RateLimitedSMSGatewayNETWORK` (bridge driver)

## Locking Strategy: Phase 1 vs Phase 2

### Phase 1: Postgres Row Lock Held Across Kafka Call

In Phase 1, the `SELECT ... FOR UPDATE` row lock is held for the entire send path — from reading the row through the Kafka publish to `COMMIT`. The rate limit decision and counter increment must be atomic.

```
Request A (user_id=101, 1 slot left):
  BEGIN
  SELECT ... FOR UPDATE  ← acquires row lock
  Check: currentCount(9) < rateLimit(10) → allowed
  sendToKafka ... waiting for ack ... (5-50ms)
  Kafka ack received
  UPDATE currentCount = 10, save()
  COMMIT ← releases row lock

Request B (same user, arrives during Request A):
  BEGIN
  SELECT ... FOR UPDATE  ← BLOCKED waiting for Request A lock
  ... waiting ...
  ... Request A commits, lock released ...
  Row now shows currentCount=10
  Check: currentCount(10) >= rateLimit(10) → DENIED
  throw RateLimitExceededException
  → 429 Too Many Requests
```

Lock duration = DB read + Kafka round-trip + DB write. Acceptable because:
- Lock is per-user row, not table-wide — different users are fully parallel
- Kafka acks are typically fast (single-digit ms in a healthy cluster)
- Virtual Threads mean blocked requests don't consume OS threads while waiting

### Phase 2: Redis Eliminates the Lock-Across-Kafka Problem

Redis changes the game because the rate limit check and counter increment happen via `CrudRepository` operations (sub-millisecond), independent of the Kafka publish. No Postgres lock is held.

```
Request A (user_id=101, 1 slot left):
  queryRedis(101) → RedisEntity{currentCount=9, rateLimit=10}
  checkRateLimitRedis → 10 <= 9? No → allowed
  incrementCurrentCount → delete + save → currentCount=10
  sendToKafka (no lock held)
  updateCurrentCountWithoutDBHit(101, 10) → sync to Postgres
  → 200 OK

Request B (same user, arrives 0.2ms later):
  queryRedis(101) → RedisEntity{currentCount=10, rateLimit=10}
  checkRateLimitRedis → 10 <= 10? Yes → DENIED
  throw RateLimitExceededException
  → 429 Too Many Requests
```

**Note**: The current `CrudRepository` approach (delete + save) is NOT atomic. Under high concurrency for the same user, two requests could read `currentCount=9` before either increments. This is acceptable for low-concurrency scenarios. Lua script atomicity (future scope) eliminates this race condition.

### Comparison

| Aspect | Phase 1 (Postgres) | Phase 2 (Redis CrudRepository) |
|--------|-------------------|-------------------------------|
| Lock duration | DB read + Kafka RTT + DB write | No lock (CrudRepository ops) |
| Concurrent same-user requests | Serialized (blocked on row lock) | Parallel (race condition possible) |
| Kafka publish | Inside lock (blocks others) | Outside lock (non-blocking) |
| Counter increment | After Kafka ack, inside transaction | Before Kafka publish (over-count tradeoff) |
| Failure handling | ROLLBACK undoes everything cleanly | Counter already incremented; Kafka fail = over-counted |
| Postgres sync | Real-time (same transaction) | Async via updateCurrentCountWithoutDBHit |
| Atomicity | Full (SELECT FOR UPDATE) | Not atomic (future: Lua script) |

### Phase 2 Tradeoff: Counter Increment Before Kafka

Redis increments the counter BEFORE Kafka publish. If Kafka fails, the counter is already incremented (over-counted). This is deliberate:
- Over-counting (slightly more restrictive on rare Kafka failures) is safer than under-counting (allowing limit bypass)
- A compensating decrement would create a race condition window where concurrent requests could bypass the rate limit
- The user loses one rate limit slot for a message that was never sent — acceptable tradeoff

## Correctness Properties

### Property 1: Config persistence round trip

For any valid userId (>= 0), rateLimit (> 0), and timeWindow (> 0), posting to `/api/config` returns HTTP 200 and the persisted row has `currentCount = 0` and `lastRefreshTime` approximately equal to now. Must hold for both inserts and updates.

**Validates: Requirements 1.1, 1.3**

### Property 2: Invalid config rejection

For any POST to `/api/config` where at least one field fails `@Valid` (e.g., rateLimit < 1, timeWindow < 1), the Gateway API returns HTTP 400 and the `PostgresEntity` table remains unchanged.

**Validates: Requirements 1.2**

### Property 3: Lazy refresh resets counter on window expiry

For any user whose `lastRefreshTime + timeWindow <= now`, a send request succeeds (HTTP 200), resets `currentCount` to 1, and updates `lastRefreshTime` — regardless of previous `currentCount`.

**Validates: Requirements 2.2**

### Property 4: Rate limit enforcement within active window

For any user within their active window (`diffMinutes < timeWindow`), send returns HTTP 200 iff `currentCount < rateLimit`. If `currentCount >= rateLimit`, returns HTTP 429 and count remains unchanged.

**Validates: Requirements 2.3, 2.4**

### Property 5: Count increment only after Kafka confirmation (Phase 1)

For any send request passing the rate limit check in Phase 1, if Kafka publish fails, `currentCount` must not be incremented and Gateway returns HTTP 503 (via `KafkaPublishException`).

**Validates: Requirements 2.6, 4.2, 4.3**

### Property 6: Concurrency safety — exactly one wins (Phase 1)

For any user with exactly 1 slot remaining, if N concurrent requests arrive via the Postgres path, exactly 1 succeeds (HTTP 200) and N-1 are rejected (HTTP 429). Final `currentCount` equals `rateLimit`. Guaranteed by `SELECT ... FOR UPDATE`.

**Validates: Requirements 3.2**

### Property 7: Kafka message correctness

For any successful send request (HTTP 200), a message on `sms-outbound` contains the userId and message payload from the original request.

**Validates: Requirements 4.1**

### Property 8: Sender Service log output

For any valid message consumed from `sms-outbound`, the `@KafkaListener` logs a message containing "SMS Sent" and the message content.

**Validates: Requirements 5.2**

### Property 9: Redis cache-first with miss-populate (Phase 2)

For any send request in Phase 2, if Redis contains `RateLimiterCache:{userId}`, the system uses it without querying Postgres. On cache miss, the system fetches from Postgres (no lock), calculates TTL, and populates Redis. The rate limiting decision should match what the data dictates.

**Validates: Requirements 9.1, 9.2, 10.1**

### Property 10: Config update invalidates Redis cache (Phase 2)

For any config update via `/api/config`, the Redis Hash at `RateLimiterCache:{userId}` is deleted, so the next send request triggers a cache miss and fetches fresh state from Postgres.

**Validates: Requirements 9.3**

### Property 11: Redis fallback to Postgres (Phase 2)

When Redis is unreachable, the `SendMessage` controller catches Redis exceptions and falls back to `sendMessagePostgres`, which uses `SELECT ... FOR UPDATE` for full concurrency safety. No requests bypass the rate limit due to Redis being down.

**Validates: Requirements 10.5, 11.5**

## Error Handling Summary

### Send Path Error Flow

```
SendMessage Controller
├── try: sendMessageRedisService.sendMessageRedis()
│   ├── RedisService.queryRedis() → cache hit or miss
│   ├── Cache miss → fetchUserFromPostgresWithoutLock()
│   │   └── Not found → UserNotFoundException (404)
│   ├── checkRateLimitRedis() → RateLimitExceededException (429)
│   ├── incrementCurrentCount() → Redis ops
│   ├── sendToKafka() → KafkaPublishException (503)
│   └── updateCurrentCountWithoutDBHit() → Postgres sync
├── catch Redis exceptions → fallback to sendMessagePostgres()
│   ├── findByUserIdForUpdate() → UserNotFoundException (404)
│   ├── checkRateLimitAndUpdateRefreshTimePostgres() → RateLimitExceededException (429)
│   ├── sendToKafka() → KafkaPublishException (503)
│   └── updateCurrentRateCountPostgres() → save
└── All exceptions → GlobalExceptionRepository (@RestControllerAdvice)
```

### Transaction Behavior

- Phase 1 (`sendMessagePostgres`): `@Transactional` — if `KafkaPublishException` is thrown, the entire transaction rolls back. `currentCount` is never incremented for failed Kafka publishes.
- Phase 2 (`sendMessageRedis`): No `@Transactional` on the Redis path. Redis increment happens before Kafka. If Kafka fails, Redis counter is already incremented (over-count). Postgres sync via `updateCurrentCountWithoutDBHit` is a separate `@Transactional` call.

## Testing Strategy

### Dual Testing Approach

- **Unit tests**: Verify specific examples, edge cases, error conditions.
- **Property-based tests (jqwik)**: Verify universal properties across randomly generated inputs.

### Property-Based Testing Configuration

- Library: [jqwik](https://jqwik.net/) — property-based testing engine for JUnit 5
- Minimum iterations: 100 per property test (`@Property(tries = 100)`)
- Tag format: `// Feature: rate-limited-notification-gateway, Property N: ...`

### Integration Tests (TestContainers)

- `@Testcontainers` with Postgres, Kafka, Redis containers
- Full flow: configure → send up to limit → verify 429 → wait for window → verify reset
- Concurrency: configure limit=1, fire N parallel requests, assert exactly 1 succeeds
- Kafka failure: stop Kafka container, verify 503 and count unchanged
- Redis fallback: stop Redis container, verify Postgres path works
- Cache invalidation: update config, verify Redis entry deleted

### Property Test to Requirement Mapping

| Property | Requirements |
|----------|-------------|
| 1: Config round trip | 1.1, 1.3 |
| 2: Invalid config rejection | 1.2 |
| 3: Lazy refresh reset | 2.2 |
| 4: Rate limit enforcement | 2.3, 2.4 |
| 5: Count after Kafka failure | 2.6, 4.2, 4.3 |
| 6: Concurrency exactly-one | 3.2 |
| 7: Kafka message content | 4.1 |
| 8: Sender log output | 5.2 |
| 9: Redis cache-first + populate | 9.1, 9.2, 10.1 |
| 10: Cache invalidation | 9.3 |
| 11: Redis fallback | 10.5, 11.5 |



## Future Scopes and Learning Resources

### 1. Atomicity with Redis Lua Scripts

The current Phase 2 implementation uses `CrudRepository` operations (delete + save) for counter increment, which is NOT atomic. Two concurrent requests could read the same `currentCount` before either increments. Lua scripts solve this.

**What to improve**:
- Replace the `incrementCurrentCount` (delete + save) with an atomic `EVAL` Lua script that does `GET` + compare + `INCR` in a single Redis command — eliminating the race condition entirely.
- Implement sliding window rate limiting using sorted sets (`ZADD` + `ZRANGEBYSCORE` + `ZCARD`) inside Lua for smoother rate limiting without burst-at-boundary issues.
- Add an idempotent publish guard: store a request ID in Redis (via Lua) before Kafka publish; on retry, check the ID to avoid double-counting.

**Learning resources**:
- [Redis Lua scripting documentation](https://redis.io/docs/latest/develop/interact/programmability/eval-intro/) — official guide covering `EVAL`, `EVALSHA`, key/arg passing, and atomicity guarantees.
- [Redis University RU101 — Introduction to Redis Data Structures](https://university.redis.io/) — covers sorted sets and scripting patterns.
- [Stripe's rate limiter blog post](https://stripe.com/blog/rate-limiters) — real-world sliding window implementation using Redis.

### 2. TestContainers for Comprehensive Integration Testing

The current design references TestContainers as a test-scope dependency but doesn't fully leverage it yet. Expanding this:

- **Redis TestContainer**: Add `com.redis:testcontainers-redis` for Phase 2 integration tests — spin up a real Redis instance to test cache behavior, fallback logic, and the full Redis → Postgres failover path.
- **Chaos testing**: Programmatically stop/start containers mid-test to verify failover behavior (e.g., stop Redis container → verify Postgres fallback → restart Redis → verify recovery). Use `container.stop()` and `container.start()` within test methods.
- **Reusable containers**: Use `withReuse(true)` to speed up local test runs by keeping containers alive across test classes.
- **TestContainers + jqwik**: Combine property-based tests with real infrastructure — generate random user configs and send patterns, run them against real Postgres/Kafka/Redis containers for true end-to-end property verification.

**Learning resources**:
- [TestContainers Java documentation](https://java.testcontainers.org/) — official guide for JUnit 5 integration, container lifecycle, and networking.
- [TestContainers Redis module](https://java.testcontainers.org/modules/redis/) — setup and configuration for Redis containers in tests.
- [AtomicJar blog — Testing Kafka applications with TestContainers](https://www.atomicjar.com/2023/06/testing-kafka-applications-with-testcontainers/) — patterns for Kafka producer/consumer integration tests.

### 3. OpenSearch for Notification Analytics

Adding OpenSearch enables querying and visualizing notification history at scale, decoupled from the transactional path.

**What to build**:
- **Notification audit log**: Index every send attempt (success, rate-limited, failed) into OpenSearch with fields like `userId`, `timestamp`, `status`, `message`. This keeps analytics separate from the Postgres transactional path.
- **Rate limit dashboards**: Build OpenSearch Dashboards visualizations showing per-user send rates, 429 frequencies, and peak usage windows.
- **Anomaly detection**: Use OpenSearch's anomaly detection plugin to flag unusual patterns (e.g., a user suddenly hitting limits they never approached before).
- **Implementation approach**: Add a second Kafka consumer that reads from `sms-outbound` (or a dedicated `notification-events` topic) and indexes into OpenSearch. The Gateway API remains unchanged.

**Learning resources**:
- [OpenSearch documentation](https://opensearch.org/docs/latest/) — official docs covering indexing, querying, and dashboard creation.
- [Spring Data Elasticsearch (compatible with OpenSearch)](https://docs.spring.io/spring-data/elasticsearch/reference/) — Spring integration for indexing and querying.
- [OpenSearch Anomaly Detection plugin](https://opensearch.org/docs/latest/observing-your-data/ad/index/) — setup and configuration for automated anomaly detection.



# Requirements Document

## Introduction

A concurrency-safe, rate-limited notification gateway built with Spring Boot (Java 21), Kafka, and Postgres. The system exposes a REST API that allows configuring per-user rate limits and sending SMS notifications. Rate limiting uses a single-table schema (Rate_Limit_Store) with a lazy refresh pattern: counters are reset on the send path when the time window has elapsed, rather than via background jobs or separate counter tables. The implementation follows a phased approach: Phase 1 establishes the core system with Postgres-backed rate limiting, Kafka event publishing, and a Sender Service. Phase 2 adds a Redis caching layer and atomic Redis operations for high-throughput, concurrency-safe rate limiting on top of the existing Postgres foundation. The entire stack runs in Docker Compose.

## Glossary

- **Gateway_API**: The Spring Boot REST service that exposes `/api/config` and `/api/send` endpoints and orchestrates rate limiting, caching, and event publishing.
- **Rate_Limiter**: The component within the Gateway_API responsible for enforcing per-user notification rate limits using the Rate_Limit_Store. In Phase 1, the Rate_Limiter uses a lazy refresh pattern within a Postgres row-level lock to check and reset counters on the send path. In Phase 2, the Rate_Limiter uses Redis for atomic counter operations with Postgres as the fallback.
- **Rate_Limit_Store**: The single Postgres table that combines per-user rate limit configuration and counter tracking. Schema: `user_id` (PRIMARY KEY), `rate_limit` (INT, NOT NULL — max allowed calls per window), `time_window` (INT, NOT NULL — window duration in minutes), `current_count` (INT, NOT NULL, default 0 — tracks send API calls atomically), `last_refresh_time` (TIMESTAMP, NOT NULL — last time the counter was reset).
- **Redis_Cache**: The Redis instance used for storing rate limit counters and cached user configurations with a configurable TTL. Introduced in Phase 2.
- **Kafka_Broker**: The Apache Kafka instance used as the message bus between the Gateway_API and the Sender_Service.
- **Sender_Service**: The service that consumes notification events from the Kafka `sms-outbound` topic and logs delivery to the console.
- **Rate_Limit_Config**: A record representing a user's rate limit settings, including user ID, maximum allowed notifications (rate_limit), and the time window in minutes.
- **Send_Request**: A record representing an inbound notification request containing at minimum a user ID and message payload.
- **Virtual_Threads**: Java 21 lightweight threads enabled via `spring.threads.virtual.enabled=true` for high-throughput concurrent request handling.
- **Lazy_Refresh**: The pattern where the Rate_Limiter checks whether the time window has elapsed on each send request and resets the counter inline, rather than using a background job or scheduled task.

---

## Phase 1: Core System with Postgres-Backed Rate Limiting

Phase 1 delivers the full end-to-end notification flow: config endpoint, send endpoint with Postgres-based rate limiting using a single-table lazy refresh pattern, Kafka event publishing, Sender Service, Docker Compose orchestration, Java 21 features, and integration testing. No Redis is involved in this phase.

### Requirement 1: Configure Per-User Rate Limits

**User Story:** As an API consumer, I want to configure per-user rate limits via a REST endpoint, so that each user can have an individually enforced notification cap.

#### Acceptance Criteria

1. WHEN a valid POST request is received at `/api/config` with a user ID, rate_limit, and time_window, THE Gateway_API SHALL persist the row to the Rate_Limit_Store with `current_count = 0` and `last_refresh_time` set to the current timestamp, and return HTTP 200 with the saved configuration.
2. WHEN a POST request to `/api/config` is missing required fields (user ID, rate_limit, or time_window), THE Gateway_API SHALL return HTTP 400 with a descriptive error message.
3. WHEN a POST request to `/api/config` contains a user ID that already has a row in the Rate_Limit_Store, THE Gateway_API SHALL update the existing rate_limit and time_window fields, reset `current_count` to 0, and set `last_refresh_time` to the current timestamp.
4. IF the Rate_Limit_Store is unreachable during a configuration request, THEN THE Gateway_API SHALL return HTTP 503 with an error message indicating the service is temporarily unavailable.

### Requirement 2: Enforce Rate Limits on the Send Path (Postgres-Backed with Lazy Refresh)

**User Story:** As an API consumer, I want the gateway to enforce per-user rate limits when sending notifications using a lazy refresh pattern, so that no user exceeds their configured notification cap and counters reset automatically when the time window elapses.

#### Acceptance Criteria

1. WHEN a valid POST request is received at `/api/send` with a user ID and message payload, THE Rate_Limiter SHALL fetch the user's row from the Rate_Limit_Store using `SELECT ... FOR UPDATE` to acquire a row-level lock.
2. WHEN the elapsed time since `last_refresh_time` is greater than or equal to the configured `time_window`, THE Rate_Limiter SHALL reset `current_count` to 1, set `last_refresh_time` to the current timestamp, and allow the request.
3. WHEN the elapsed time since `last_refresh_time` is less than the configured `time_window` and `current_count` is below `rate_limit`, THE Rate_Limiter SHALL increment `current_count` by 1 in the Rate_Limit_Store, THE Gateway_API SHALL publish a notification event to the Kafka `sms-outbound` topic, and return HTTP 200.
4. WHEN the elapsed time since `last_refresh_time` is less than the configured `time_window` and `current_count` is greater than or equal to `rate_limit`, THE Gateway_API SHALL return HTTP 429 Too Many Requests with a message indicating the rate limit has been exceeded.
5. WHEN a POST request to `/api/send` references a user ID with no row in the Rate_Limit_Store, THE Gateway_API SHALL return HTTP 404 with a message indicating no configuration exists for the user.
6. THE Rate_Limiter SHALL increment `current_count` in the Rate_Limit_Store only after the Kafka publish is confirmed, to prevent counting notifications that were not successfully queued.

### Requirement 3: Postgres-Based Concurrency Control with Lazy Refresh

**User Story:** As a system operator, I want rate limit checks, lazy refresh, and increments to be safe under concurrent requests, so that parallel requests cannot bypass the rate limit.

#### Acceptance Criteria

1. THE Rate_Limiter SHALL use a Postgres transaction with `SELECT ... FOR UPDATE` on the Rate_Limit_Store row to perform the lazy refresh check, counter evaluation, and increment atomically within a single transaction.
2. WHEN two or more concurrent requests arrive for the same user and only one slot remains, THE Rate_Limiter SHALL allow exactly one request and reject the others with HTTP 429.
3. WHEN the Rate_Limiter acquires the row lock and determines that the elapsed time since `last_refresh_time` exceeds the configured `time_window`, THE Rate_Limiter SHALL reset `current_count` and update `last_refresh_time` before evaluating the rate limit within the same transaction.

### Requirement 4: Kafka Event Publishing

**User Story:** As a system architect, I want notification events published to Kafka, so that the send path is decoupled from the actual delivery mechanism.

#### Acceptance Criteria

1. WHEN a notification is allowed by the Rate_Limiter, THE Gateway_API SHALL publish a message to the Kafka `sms-outbound` topic containing the user ID and message payload.
2. IF the Kafka_Broker is unreachable when publishing a notification event, THEN THE Gateway_API SHALL return HTTP 503 and SHALL NOT increment `current_count` in the Rate_Limit_Store for that request.
3. THE Gateway_API SHALL increment `current_count` in the Rate_Limit_Store only after the Kafka publish is confirmed, to prevent counting notifications that were not successfully queued.

### Requirement 5: Sender Service Consumption

**User Story:** As a system operator, I want a consumer service that processes notification events from Kafka, so that SMS delivery is handled asynchronously.

#### Acceptance Criteria

1. THE Sender_Service SHALL consume messages from the Kafka `sms-outbound` topic.
2. WHEN the Sender_Service receives a notification event, THE Sender_Service SHALL log "SMS SENT" along with the user ID and message payload to the console.
3. IF the Sender_Service encounters a malformed message on the topic, THEN THE Sender_Service SHALL log an error with the message details and skip the message without crashing.

### Requirement 6: Docker Compose Orchestration (Phase 1)

**User Story:** As a developer, I want the entire stack to run via `docker-compose up --build`, so that I can start the full system with a single command.

#### Acceptance Criteria

1. THE Docker_Compose configuration SHALL define services for the Gateway_API, Sender_Service, Postgres, and Kafka_Broker.
2. THE Docker_Compose configuration SHALL use a named volume for Postgres data so that data persists across container restarts.
3. THE Gateway_API and Sender_Service SHALL wait for the Rate_Limit_Store (Postgres) and Kafka_Broker to be healthy before starting, using health checks or retry-based readiness strategies.
4. WHEN `docker-compose up --build` is executed, THE system SHALL build and start all services without manual intervention.

### Requirement 7: Java 21 Feature Usage

**User Story:** As a developer, I want the application to leverage Java 21 features, so that the codebase uses modern language capabilities for performance and readability.

#### Acceptance Criteria

1. THE Gateway_API SHALL enable Virtual_Threads via the `spring.threads.virtual.enabled=true` configuration property.
2. THE Gateway_API and Sender_Service SHALL use Java Records for all Data Transfer Objects (request and response bodies).

### Requirement 8: Integration Testing (Phase 1)

**User Story:** As a developer, I want at least one integration test using TestContainers, so that the rate limiting behavior is verified against real infrastructure.

#### Acceptance Criteria

1. THE test suite SHALL include at least one integration test that uses TestContainers to spin up Postgres and Kafka.
2. WHEN the integration test sends notifications up to the configured limit, THE test SHALL verify that subsequent requests receive HTTP 429.
3. THE integration test SHALL verify the full flow: configure a rate limit (verifying `current_count` initializes to 0), send notifications, and confirm rate limiting is enforced via the lazy refresh pattern.

---

## Phase 2: Redis Caching Layer

Phase 2 adds Redis on top of the established Postgres-backed system using Spring Data Redis `@RedisHash` with `CrudRepository`. Redis stores a single Hash per user at key `RateLimiterCache:{userId}` containing `rateLimit`, `currentCount`, and a `@TimeToLive` TTL (in seconds). The Hash TTL is set to the remaining time in the current window (`lastRefreshTime + timeWindow - now`), so counters auto-reset when the window expires — replacing the lazy refresh pattern from Phase 1 on the Redis path. On cache miss, the system queries Postgres and populates Redis. Postgres remains the source of truth, with Redis acting as the hot-path accelerator. Atomic Lua script operations are deferred to future scope — the current `CrudRepository` approach is acceptable for single-user/low-concurrency scenarios.

### Requirement 9: Redis Hash Cache for Rate Limit State

**User Story:** As a system operator, I want per-user rate limit state cached in a Redis Hash, so that the send path avoids querying Postgres on every request and counters reset automatically via TTL expiry.

#### Acceptance Criteria

1. WHEN the Gateway_API processes a send request, THE Rate_Limiter SHALL first check Redis for a Hash at key `RateLimiterCache:{userId}` containing `currentCount` and `rateLimit` before querying the Rate_Limit_Store.
2. WHEN the Redis Hash exists for the user, THE Rate_Limiter SHALL use the cached `currentCount` and `rateLimit` for the rate limit decision without querying the Rate_Limit_Store.
3. WHEN a Rate_Limit_Config is updated via the `/api/config` endpoint, THE Gateway_API SHALL delete the Redis Hash at `RateLimiterCache:{userId}` so that stale configurations are not used. If Redis is unreachable during invalidation, the Gateway_API SHALL log the error and continue (config update still succeeds in Postgres).
4. THE Redis Hash TTL SHALL be set to the remaining time in the current window: `(timeWindow - diffMinutes) * 60` seconds. If the window has expired (`diffMinutes >= timeWindow`), the TTL SHALL be set to `timeWindow * 60` seconds and `currentCount` SHALL start at 0.

### Requirement 10: Cache Miss Strategy

**User Story:** As a system operator, I want Redis to be populated from Postgres on cache miss, so that the system remains functional after Redis restarts or evictions without requiring manual intervention.

#### Acceptance Criteria

1. WHEN Redis does not contain a Hash for a given user ID, THE Rate_Limiter SHALL fetch the user's row from the Rate_Limit_Store using `findById` (no lock), calculate the remaining window TTL, and populate a Redis Hash at `RateLimiterCache:{userId}` with `rateLimit`, `currentCount` (carried over from Postgres when window is active), and TTL in seconds.
2. IF the window has expired (`diffMinutes >= timeWindow`), THE Rate_Limiter SHALL populate the Redis Hash with `currentCount = 0`, TTL set to `timeWindow * 60` seconds, and update `lastRefreshTime` and reset `currentCount = 0` in the Rate_Limit_Store.
3. IF the window is still active (`diffMinutes < timeWindow`), THE Rate_Limiter SHALL populate the Redis Hash with `currentCount = postgresEntity.getCurrentCount()` and TTL set to `(timeWindow - diffMinutes) * 60` seconds.
4. IF the Rate_Limit_Store is unreachable during a cache miss, THEN THE Gateway_API SHALL return HTTP 503 with an error message indicating the service is temporarily unavailable (via `DataAccessException` → `GlobalExceptionRepository`).
5. IF the Redis_Cache is unreachable, THEN THE SendMessage controller SHALL catch `RedisConnectionFailureException | RedisCommandTimeoutException | RedisSystemException`, log "REDIS DOWN — Falling back to Postgres", and fall back to `SendMessagePostgresService.sendMessagePostgres()` which uses the Phase 1 `SELECT ... FOR UPDATE` lazy refresh behavior.

### Requirement 10.5: Config Path — Invalidate-Only, No Redis Write

**User Story:** As a system operator, I want the config endpoint to only invalidate Redis (not write to it), so that Redis is populated exclusively on the send path and the config path remains simple.

#### Acceptance Criteria

1. WHEN a Rate_Limit_Config is created or updated via `/api/config`, THE Gateway_API SHALL only upsert the row in Postgres (via `UpsertDB.insertUserConfigToDB`) and delete (`DEL`) the Redis Hash at `RateLimiterCache:{userId}` (via `RedisService.deleteUser`). It SHALL NOT write any new data to Redis.
2. IF Redis is unreachable during the invalidation, THE Gateway_API SHALL catch the exception, log "REDIS DOWN — Falling back to Postgres", and continue — the config update still succeeds in Postgres.
3. Redis SHALL be populated exclusively on the send path: when `/api/send` encounters a cache miss, it fetches from Postgres and populates the Redis Hash with the appropriate TTL.

### Requirement 11: Redis Rate Limit Enforcement (Current: CrudRepository, Future: Lua Script)

**User Story:** As a system operator, I want rate limit checks and increments in Redis, so that the send path avoids Postgres row locks under normal operation.

#### Acceptance Criteria (Current Implementation)

1. THE Rate_Limiter SHALL use `RedisService.incrementCurrentCount()` to increment `currentCount` in the Redis Hash via `CrudRepository` delete + save operations. This is NOT atomic — under high concurrency, race conditions are possible.
2. THE Rate_Limiter SHALL check `rateLimit <= currentCount` before incrementing. If the limit is reached, throw `RateLimitExceededException` (HTTP 429).
3. THE Redis Hash TTL handles counter reset automatically. When the TTL expires, the Hash is deleted, and the next request triggers a cache miss that fetches fresh state from Postgres.
4. THE Rate_Limiter SHALL sync `currentCount` to the Rate_Limit_Store after each successful send via `updateCurrentCountWithoutDBHit` (`@Modifying` JPQL UPDATE, no SELECT needed).

#### Acceptance Criteria (Future Scope — Lua Script Atomicity)

5. THE Rate_Limiter SHALL use a Lua script executed via `RedisTemplate.execute(RedisScript)` to atomically read `currentCount` and `rateLimit` from the user's Hash, compare them, and increment `currentCount` if allowed — all in a single atomic operation.
6. WHEN two or more concurrent requests arrive for the same user and only one slot remains, THE Lua script SHALL allow exactly one request and reject the others with HTTP 429. This is guaranteed by Redis's single-threaded execution model — Lua scripts cannot be interleaved.

### Requirement 11.6: Redis Counter Increment Before Kafka — Tradeoff

**User Story:** As a system operator, I want to understand that Redis increments the counter before Kafka publish, so that I know the system favors safety (no limit bypass) over perfect accuracy on rare Kafka failures.

#### Acceptance Criteria

1. THE Rate_Limiter SHALL increment `currentCount` in the Redis Hash BEFORE publishing to Kafka. This is different from Phase 1 where the Postgres counter is incremented AFTER Kafka confirmation.
2. IF Kafka publish fails after Redis has already incremented `currentCount`, THE counter SHALL remain incremented (over-counted). The user loses one rate limit slot for a message that was never sent.
3. THE Rate_Limiter SHALL NOT attempt to decrement the Redis counter on Kafka failure, because a compensating decrement creates a race condition window where concurrent requests could bypass the rate limit.
4. This is a deliberate tradeoff: over-counting (slightly more restrictive on rare Kafka failures) is safer than under-counting (allowing limit bypass under concurrency).

### Requirement 11.5: Redis Concurrency Model

**User Story:** As a system operator, I want to understand the concurrency characteristics of the Redis path.

#### Acceptance Criteria

1. The current implementation uses `CrudRepository` operations (findById, deleteById, save) which are NOT atomic. Under high concurrency for the same user, race conditions are possible where two requests read the same `currentCount` before either increments.
2. This is acceptable for single-user or low-concurrency scenarios. For high-throughput production use, Lua script atomicity (Requirement 11, future scope) should be implemented.
3. Redis is single-threaded — it processes one command at a time. A Lua script runs atomically within Redis, meaning no other command can execute between the steps of the script. This will eliminate the race condition when implemented.
4. IF Redis is unreachable, THE SendMessage controller SHALL catch Redis exceptions and fall back to the Phase 1 Postgres path which uses `SELECT ... FOR UPDATE` for full concurrency safety.

### Requirement 12: Docker Compose Orchestration (Phase 2)

**User Story:** As a developer, I want Redis added to the Docker Compose stack, so that the full system including caching runs with a single command.

#### Acceptance Criteria

1. THE Docker_Compose configuration SHALL add a Redis service to the existing Phase 1 services.
2. THE Gateway_API SHALL wait for the Redis_Cache to be healthy before starting, in addition to the existing Phase 1 health check dependencies.
3. WHEN `docker-compose up --build` is executed, THE system SHALL build and start all services including Redis without manual intervention.

### Requirement 13: Integration Testing (Phase 2)

**User Story:** As a developer, I want integration tests that verify Redis-backed rate limiting, so that the caching and atomic operations are validated against real infrastructure.

#### Acceptance Criteria

1. THE test suite SHALL include at least one integration test that uses TestContainers to spin up Postgres, Redis, and Kafka.
2. WHEN the integration test sends notifications up to the configured limit, THE test SHALL verify that subsequent requests receive HTTP 429 using the Redis-backed Rate_Limiter.
3. THE integration test SHALL verify the cache miss flow: send a request with a cold Redis cache and confirm the Rate_Limiter fetches the configuration from the Rate_Limit_Store and populates the Redis Hash with the correct TTL.
4. THE integration test SHALL verify that when Redis is unavailable, the Rate_Limiter falls back to Postgres-based rate limiting using the Rate_Limit_Store with `SELECT ... FOR UPDATE` lazy refresh.
