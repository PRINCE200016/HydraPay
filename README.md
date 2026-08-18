# HydraPay - Global Idempotent Settlement & Ledger Engine

![Java](https://img.shields.io/badge/Java-21-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.3-brightgreen.svg)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)
![Redis](https://img.shields.io/badge/Redis-7-red.svg)
![Kafka](https://img.shields.io/badge/Apache%20Kafka-KRaft-black.svg)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)

**HydraPay** is a high-throughput, FinTech-grade global idempotent settlement & double-entry ledger engine engineered for **10,000+ TPS** with **exactly-once processing guarantees** and **zero double-debit errors**.

---

## Key Features

- **Double-Entry Bookkeeping**: Immutable ledger entries (`DEBIT` and `CREDIT` balance pairs) guaranteeing strict mathematical net-zero balance invariants across all transactions.
- **Deterministic Account Locking Strategy**: Prevents PostgreSQL row-level deadlocks (`SELECT FOR UPDATE`) under heavy concurrent transfers by sorting Account UUIDs before lock acquisition.
- **2-Tier Distributed Idempotency**: Fast L1 Redis Distributed Lock (`SETNX`) with persistent L2 PostgreSQL unique constraint fallback.
- **Batched Transactional Outbox Pattern**: Asynchronous, batched event publishing to Apache Kafka with At-Least-Once delivery guarantees (`CompletableFuture.allOf(...)` and `repository.saveAll(...)`).
- **Micrometer & Actuator Observability**: Real-time operational metrics exposed at `/actuator/metrics`, `/actuator/prometheus`, `/actuator/health` (`transactions.processed` counter and `transaction.latency` timer).
- **Scheduled Ledger Invariant Reconciliation**: Scheduled hourly detector (`ReconciliationService`) auditing system net balance invariants ($\sum \text{amount} = 0$) and per-account balance records.
- **Concurrent Load Testing Simulator**: High-throughput multi-threaded load generator (`scripts/load_test.py`) testing TPS, latency percentiles (P50/P90/P95/P99), and idempotency hits.
- **Operations & Systems Dashboard**: Interactive web dashboard featuring real-time TPS monitoring, live ledger feeds, outbox queue metrics, and simulated transfer execution.

---

## Repository Structure

```
HydraPay/
├── backend/
│   ├── src/
│   │   ├── main/java/com/hydrapay/ledger/
│   │   │   ├── controller/      # REST API Controllers
│   │   │   ├── domain/          # Entities & Enums (Account, LedgerTransaction, LedgerEntry)
│   │   │   ├── dto/             # Data Transfer Objects
│   │   │   ├── exception/       # Exception Handlers
│   │   │   ├── repository/      # Spring Data Repositories (Pessimistic Locking)
│   │   │   └── service/         # LedgerService, IdempotencyService, OutboxPublisherService
│   │   └── resources/
│   │       ├── db/migration/    # Flyway V1 Database Schema Migration
│   │       └── application.yml  # Database & Spring Configurations
│   ├── Dockerfile               # Multi-stage Java 21 Container Image
│   └── pom.xml                  # Maven Dependencies
├── frontend/
│   └── index.html               # Operations Dashboard UI
├── docs/
│   ├── SYSTEM_ARCHITECTURE.md   # Architectural Deep Dive & Mermaid Diagrams
│   ├── API_DOCUMENTATION.md     # REST Contracts & cURL Examples
│   ├── DATABASE_DESIGN.md       # ER Diagram & Double-Entry Invariant Proof
│   ├── FAILURE_SCENARIOS.md     # Fault Matrix & Resilience Scenarios
│   └── LOAD_TEST_REPORT.md      # 10,000+ TPS Load Test Metrics & JVM Tuning
├── docker-compose.yml           # Full Stack Orchestration (Postgres, Redis, Kafka, Backend, UI)
└── README.md                    # Project Documentation
```

---

## Quickstart Guide

### Option 1: Docker Compose (Recommended)
Launch the entire production stack (PostgreSQL, Redis, Apache Kafka, Spring Boot Engine, Nginx UI) with a single command:

```bash
docker-compose up --build -d
```

- **Backend API**: `http://localhost:8080/api/v1`
- **Dashboard UI**: `http://localhost:3000`

---

### Option 2: Local Development Setup

#### Prerequisites
- JDK 21+
- Maven 3.9+
- PostgreSQL 16 running on `localhost:5432`
- Redis 7 running on `localhost:6379`

#### Step 1: Run Flyway Database Migration & Start Engine
```bash
cd backend
mvn clean spring-boot:run
```

#### Step 2: Open Dashboard UI
Open `frontend/index.html` directly in your web browser.

---

## Testing & Verification

Run the automated JUnit 5 test suite:

```bash
cd backend
mvn clean test
```

### Sample Transfer API Request
```bash
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: idk_demo_1001" \
  -d '{
    "idempotencyKey": "idk_demo_1001",
    "sourceAccountId": "00000000-0000-0000-0000-000000000001",
    "destinationAccountId": "00000000-0000-0000-0000-000000000002",
    "amount": 250.00,
    "currency": "USD",
    "description": "Initial Merchant Settlement"
  }'
```

---

## Documentation Suite

- [System Architecture & Topology](file:///e:/HydraPay/docs/SYSTEM_ARCHITECTURE.md)
- [Production Security & API Hardening](file:///e:/HydraPay/docs/SECURITY.md)
- [REST API Specification](file:///e:/HydraPay/docs/API_DOCUMENTATION.md)
- [Database ERD & Double-Entry Math](file:///e:/HydraPay/docs/DATABASE_DESIGN.md)
- [Failure Scenarios & Fault Matrix](file:///e:/HydraPay/docs/FAILURE_SCENARIOS.md)
- [10,000+ TPS Load Test Report](file:///e:/HydraPay/docs/LOAD_TEST_REPORT.md)
