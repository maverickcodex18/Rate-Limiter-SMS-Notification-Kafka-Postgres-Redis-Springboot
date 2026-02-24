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