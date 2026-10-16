# LogStream — Distributed Log Analytics & Alerting Platform

**Project:** Project 2  
**Domain:** Observability & Big Data

LogStream is a self-hosted log analytics platform designed to ingest, index, search, and analyze application logs. It combines gRPC-based ingestion, Apache Lucene indexing, a Spring Boot backend, PostgreSQL metadata storage, and a React dashboard.

> LogStream aims to provide a centralized place to investigate application logs, explore trends, and manage alert rules.

## Key Capabilities

- **Log ingestion:** Receive application logs through gRPC and Protocol Buffers.
- **Indexed search:** Search logs using Apache Lucene rather than scanning raw log records in a relational database.
- **Querying:** Filter logs by fields such as level, service, and response time.
- **Analytics:** Explore log-volume histograms and term distributions.
- **Alerting:** Manage alert rules and support webhook or email actions.
- **Live Tail:** Stream log updates to the dashboard through WebSocket communication.
- **Tenant context:** Associate requests and log data with a tenant.

> Confirm each capability against the current implementation before describing it as production-ready. Performance figures in this README are targets unless accompanied by benchmark results.

## Table of Contents

- [Why this project exists](#why-this-project-exists)
- [Architecture](#high-level-architecture)
- [Technology stack](#technology-stack)
- [Repository structure](#repository-structure)
- [Running locally](#running-locally)
  - [Prerequisites](#prerequisites)
  - [Database setup](#database-setup)
  - [Backend setup](#backend-setup)
  - [Frontend setup](#frontend-setup)
  - [Generate test log traffic](#generate-test-log-traffic)
  - [Troubleshooting](#troubleshooting)
- [Core API surface](#core-api-surface)
- [Documentation map](#documentation-map)
- [Performance targets](#performance-targets)
- [Project status and future improvements](#project-status-and-future-improvements)

## Why this project exists

Searching large volumes of application logs with broad relational-database queries can become expensive. For example, a query such as `WHERE message LIKE '%timeout%'` may require scanning many rows.

LogStream uses an inverted index in Apache Lucene to support text search. PostgreSQL is intended for metadata such as tenants and alert rules, while Lucene handles raw log search.

Example query:

```text
level:ERROR AND service:billing-api AND response_time > 1000
```

The actual query syntax and supported fields depend on the implementation in the repository.

## High-level architecture

```text
Microservices / log producers
            |
            | gRPC + Protocol Buffers
            v
+--------------------------------------+
| LogStream backend (Spring Boot)      |
|                                      |
| gRPC server -> async index pipeline  |
|                    |                 |
|                    v                 |
|              Apache Lucene           |
|                    |                 |
|              Search service          |
|                    |                 |
|        REST API / analytics          |
|                    |                 |
|              React dashboard         |
|                                      |
| Alert scheduler -> webhook / email   |
| WebSocket -> Live Tail               |
+--------------------------------------+
            |
            v
PostgreSQL metadata
(tenants, alert rules, alert events)
```

The detailed component diagrams, responsibilities, and request flows are documented in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

## Technology stack

| Layer | Technology | Purpose |
|---|---|---|
| Ingestion | Java 21, gRPC, Protocol Buffers | Receiving application logs |
| Search and indexing | Apache Lucene 10.3 | Full-text indexing and search |
| Backend API | Spring Boot 3.5, Spring Web | Application services and REST APIs |
| Persistence | Spring Data JPA, PostgreSQL 15 | Metadata persistence |
| Streaming | Spring WebSocket | Live Tail updates |
| Email | Spring Mail | Email notifications, where configured |
| Frontend | React 19, TypeScript, Vite | Dashboard user interface |
| Visualization | Apache ECharts | Charts and analytics |
| Build tools | Maven, npm | Dependency management and builds |

Verify the versions against the Maven and npm manifests if the dependencies change.

## Repository structure

```text
project_axlero/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── IMPLEMENTATION_PLAN.md
│   ├── INDEXING_STRATEGY.md
│   └── MULTI_TENANCY.md
├── logstream-main/
│   ├── backend/
│   │   └── logstream-backend/
│   │       └── src/
│   │           └── main/
│   │               ├── proto/
│   │               │   └── log_service.proto
│   │               ├── java/logstream_backend/
│   │               └── resources/
│   │                   └── application.properties
│   └── frontend/
│       └── src/
│           ├── pages/
│           ├── components/
│           └── services/
│               └── api.ts
└── postman/
```

This tree is a guide to the documented layout. Update it if the repository's actual paths differ.

## Running locally

### Prerequisites

Install the tools required by the project:

- JDK 21
- Maven 3.9+ (optional if you use the included Maven Wrapper)
- Node.js 20+ and npm 10+
- PostgreSQL 15
- Git

Check your installed versions:

```bash
java -version
mvn -version
node -v
npm -v
git --version
```

### Database setup

Create the database in PostgreSQL:

```sql
CREATE DATABASE logstream;
```

Ensure PostgreSQL is running and that the backend's database connection settings match your local environment. The original project instructions state that Hibernate creates or updates the schema and that additional index DDL is documented in `docs/INDEXING_STRATEGY.md`.

### Backend setup

From the repository root, run:

**macOS / Linux / Git Bash**

```bash
cd logstream-main/backend/logstream-backend
./mvnw clean spring-boot:run
```

**Windows PowerShell**

```powershell
cd logstream-main/backend/logstream-backend
.\mvnw.cmd clean spring-boot:run
```

Before starting the backend, configure the database credentials and any required SMTP settings in the application's configuration. Do not commit real passwords, API keys, or other secrets.

The documented service addresses are:

| Service | Address |
|---|---|
| REST API | `http://localhost:8080` |
| gRPC ingestion | `localhost:9090` |
| WebSocket Live Tail | `ws://localhost:8080/ws/logs` |
| Health endpoint | `http://localhost:8080/actuator/health` |

These addresses assume the default local configuration.

### Frontend setup

Open another terminal:

```bash
cd logstream-main/frontend
npm install
npm run dev
```

Vite prints the local development URL in the terminal when the server starts. The default documented URL is `http://localhost:5173`.

If the frontend cannot reach the backend, verify the API base URL and the backend CORS configuration.

### Generate test log traffic

The repository documentation refers to the Postman collection in [`postman/`](postman/) and the `grpcurl` command-line tool for sending a sample log.

Example request from the original project instructions:

```bash
grpcurl -plaintext -d '{"log":{"tenantId":"acme","timestamp":"2026-10-02T10:00:00Z","level":"ERROR","service":"billing-api","message":"db timeout","host":"node-1","responseTime":1420}}' \
  localhost:9090 logstream.LogService/SendLog
```

This command assumes that the gRPC service and request schema match the example and that the server is running locally.

### Troubleshooting

**Backend reports a datasource error**

- Confirm that PostgreSQL is running.
- Verify the database name, host, port, username, and password.
- Confirm that the database exists.

**Port 8080 or 9090 is already in use**

On Windows, inspect listening processes:

```cmd
netstat -ano | findstr LISTENING | findstr ":8080 :9090"
```

Stop only the process you have identified as safe to stop, or configure the application to use an available port.

**Frontend cannot connect to the backend**

- Confirm that the backend has started successfully.
- Check the frontend API base URL.
- Verify that the CORS configuration permits the frontend origin.

**Maven compilation or class-loading problems**

Try a clean build using the Maven Wrapper:

```bash
./mvnw clean compile
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean compile
```

The original project notes also recommend disabling IDE auto-build if it overwrites Maven-generated class output. Check `.vscode/settings.json` and your IDE's Maven build settings if this issue recurs.

## Core API surface

The following endpoints are documented by the project. Confirm the paths, parameters, and behavior against the current backend code.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/logs` | Retrieve recent logs |
| GET | `/api/logs/count` | Count indexed documents for a tenant |
| GET | `/api/logs/stats` | Retrieve log-level distribution and summary counters |
| GET | `/api/logs/search` | Search logs using query and filter parameters |
| GET | `/api/logs/histogram` | Retrieve log-volume time-series data |
| GET | `/api/logs/facets` | Retrieve term distributions |
| POST | `/api/alerts/rules` | Create an alert rule |
| GET | `/api/alerts/rules` | List alert rules |
| PUT | `/api/alerts/rules/{id}` | Update an alert rule |
| DELETE | `/api/alerts/rules/{id}` | Delete an alert rule |
| GET | `/api/alerts/current` | Retrieve currently firing alerts |
| GET | `/api/alerts/history` | Retrieve alert event history |
| POST | `/api/alerts/evaluate` | Trigger an immediate alert evaluation |

The project documentation states that REST requests accept tenant context through the `X-Tenant-Id` header, defaulting to `default` when it is omitted.

See [`docs/MULTI_TENANCY.md`](docs/MULTI_TENANCY.md) for tenant-related details and [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) for additional implementation notes and examples.

## Documentation map

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Component architecture and request-flow diagrams |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Implementation plan and remaining work |
| [`docs/INDEXING_STRATEGY.md`](docs/INDEXING_STRATEGY.md) | PostgreSQL and Lucene indexing strategies |
| [`docs/MULTI_TENANCY.md`](docs/MULTI_TENANCY.md) | Tenant isolation and tenant context |

## Performance targets

The original project README defines the following targets:

| Metric | Target |
|---|---|
| gRPC ingestion throughput | At least 10,000 logs/second on one machine |
| Search latency at 1 million documents | Under 50 ms p95 for filtered keyword queries |
| Histogram aggregation | Under 150 ms for 24 hours of data at 1 million documents |
| Alert evaluation cadence | Every 60 seconds, configurable |
| Live Tail end-to-end latency | Under 500 ms from gRPC receipt to browser |

**Important:** These are stated targets, not verified results. Actual performance depends on hardware, workload, index configuration, and deployment settings. Add benchmark methodology and measured results only after running tests.

## Project status and future improvements

LogStream is described as a distributed log analytics and alerting platform. Use this section to communicate the current implementation status accurately.

Potential future improvements include:

- Automated tests for ingestion, search, and alert evaluation.
- Reproducible performance benchmarks.
- Expanded API examples and error-response documentation.
- Containerized local development and deployment.
- More detailed operational monitoring and health checks.

Remove any item from this list if it is already implemented or is not part of the project's intended direction.
