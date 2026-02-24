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

## Prerequisites

- Java 21
- Maven 3.9+
- Docker & Docker Compose

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

## How Rate Limiting Works

1. `POST /api/config` upserts a row in `rate_limit_store` with `current_count = 0`
2. `POST /api/send` checks the user's counter against their limit
3. **Lazy refresh**: if `now - last_refresh_time >= time_window`, counter resets inline — no cron jobs
4. **Phase 1**: counter increments only after Kafka confirms delivery (`SELECT ... FOR UPDATE` lock held across Kafka call)
5. **Phase 2**: Redis increments counter before Kafka publish (over-count tradeoff for speed)
6. **Concurrency**: Phase 1 uses row-level locks. Phase 2 uses Redis operations (future: Lua scripts for atomicity)

## Redis Fallback

When Redis goes down, the `SendMessage` controller catches Redis exceptions and falls back to the Postgres path automatically:

- Rate limiting correctness is fully preserved
- Throughput drops for same-user bursts (row lock serialization)
- Different users remain fully parallel
- Recovery is automatic when Redis comes back

## Project Structure

```
src/main/java/com/example/RateLimitedSMSGateway/
├── Endpoints/
│   ├── CreateUserConfig.java           # POST /api/config
│   └── SendMessage.java                # POST /api/send (Redis → Postgres fallback)
├── Services/
│   ├── SendMessagePostgresService.java # Phase 1: SELECT FOR UPDATE + Kafka
│   ├── SendMessageRedisService.java    # Phase 2: Redis cache + counter
│   ├── RedisService.java               # Redis CRUD (CrudRepository)
│   ├── UpsertDB.java                   # Config persistence (upsert)
│   └── KafkaConfig.java                # Kafka producer + @KafkaListener consumer
├── Entity/
│   ├── PostgresEntity.java             # JPA entity for rate_limit_store
│   └── RedisEntity.java                # @RedisHash entity
├── Repository/
│   ├── PostgresRepository.java         # findByUserIdForUpdate (PESSIMISTIC_WRITE)
│   └── RedisRepository.java            # CrudRepository<RedisEntity, Integer>
├── DTO/
│   └── AllRecords.java                 # Request/response Java Records
└── CustomExceptions/
    ├── CustomExceptionTemplate.java    # Base exception (message + statusCode)
    ├── UserNotFoundException.java      # 404
    ├── RateLimitExceededException.java # 429
    ├── KafkaPublishException.java      # 503
    └── GlobalExceptionRepository.java  # @RestControllerAdvice handler
```

## Docker Services

| Service | Image | Port | Health Check |
|---------|-------|------|-------------|
| app | Built from Dockerfile | 8080 | depends_on all others |
| postgres | postgres:15-alpine | 5432 | `pg_isready` |
| redis | redis:8.0.6-alpine3.21 | 6379 | `redis-cli ping` |
| kafka | confluentinc/cp-kafka:7.5.0 | 9092, 29092 | `nc -z localhost 9092` |
| zookeeper | confluentinc/cp-zookeeper:7.5.0 | 2181 | — |

All services communicate over the `RateLimitedSMSGatewayNETWORK` bridge network.

## Testing

`api-tests.http` contains test cases for IntelliJ HTTP Client covering:
- Config CRUD, validation errors (400), boundary values
- Send happy path, rate limit exceeded (429), user not found (404)
- Lazy refresh (window expiry resets counter)
- Config update resets counter mid-window
- Multi-user isolation
- Rapid-fire manual concurrency testing

## Stopping

```bash
docker compose down        # stop containers
docker compose down -v     # stop + remove volumes
```
