# Smart Invoice Processing

A small, failure-focused backend system: it reads supplier invoices from PDF with a **local LLM**, validates them,
checks them against the supplier contract, and enters approved invoices into a **legacy portal that has no API** —
by driving a browser (RPA).

The scope is deliberately narrow; the failure handling is not. The system is built around two guarantees:

- **No message is silently lost.** Every outgoing message goes through a transactional outbox; every queue has a
  delivery limit and its own dead-letter queue, and dead letters are parked in the database, never dropped.
- **An invoice is entered into the portal at most once.** The portal is not idempotent, so this needs several
  layers: idempotent consumers, conditional state transitions, a single active RPA consumer, a per-invoice
  distributed lock and a search in the portal before every entry.

Four Spring Boot services talk to each other **only through RabbitMQ**; nothing calls another service over HTTP.

> Design documents in [`docs/`](docs/) are in Turkish. [`docs/DECISIONS.md`](docs/DECISIONS.md) is the single source
> of truth: scope, architecture, message contracts, ADRs and the backlog with measurements.

## Architecture

![Architecture](docs/architecture.svg)

| Component              | Responsibility                                                                                      | Port (host) |
| ---------------------- | --------------------------------------------------------------------------------------------------- | ----------- |
| `document-service`     | Orchestrator and **sole owner of invoice state**; upload, query, review and admin REST API; dead-letter handling | 8081 |
| `extraction-service`   | PDF text (PDFBox) → schema-constrained JSON from a local LLM (Ollama) → Turkish number/date parsing → validation rules → confidence score; API to inspect raw LLM answers | 8083 |
| `compliance-service`   | Contract upload and clause indexing (pgvector); invoice vs. the supplier contract valid on the invoice date (unit prices, payment term) | 8082 |
| `rpa-service`          | Logs into the legacy portal with Playwright (headless Chromium), searches for the invoice, fills the form, reads the reference number | — |
| `mock-portal`          | Stand-in for the legacy accounting portal: server-rendered HTML only, no API; injectable faults     | 8090        |
| PostgreSQL + pgvector  | One database **per service** in a single container                                                  | 5432        |
| RabbitMQ 4 (quorum)    | `invoice.commands` (direct), `invoice.events` (topic), `invoice.dlx` → one DLQ per queue, `invoice.retry` → 30-second waiting room for the portal | 5672 |
| Redis                  | Short-lived coordination only: shared LLM semaphore, per-invoice RPA lock                           | 6379        |
| Ollama                 | Runs on the host (macOS, Metal GPU), outside Docker                                                 | 11434       |

### Happy path

```
POST /api/v1/documents ──▶ RECEIVED ──ExtractInvoice──▶ extraction-service
                           EXTRACTED ◀─ExtractionCompleted─┘
                           VALIDATED   (confidence ≥ threshold, no duplicate)
                                    ──CheckCompliance──▶ compliance-service (contract RAG)
                    COMPLIANCE_CHECKED ◀─ComplianceCompleted─┘  (COMPLIANT, total ≤ approval threshold, TRY)
                        QUEUED_FOR_RPA ──PostToPortal──▶ rpa-service ──▶ legacy portal
                                POSTED ◀─RpaCompleted (portal ref. no)─┘
```

Off the happy path, a record stops and waits for a person:

| State                 | Why it gets there                                                                                         | Who moves it on |
| --------------------- | --------------------------------------------------------------------------------------------------------- | --------------- |
| `NEEDS_REVIEW`        | Low confidence, unreadable PDF, the LLM kept failing                                                      | Expert corrects the fields (re-checked by the rules, then on to compliance) or rejects |
| `DUPLICATE_SUSPECTED` | Another document has the same supplier tax number and invoice number                                    | Expert decides: duplicate (rejected) or not (on to compliance) |
| `PENDING_APPROVAL`    | Not `COMPLIANT` (price above the contract, different payment term, no valid contract, conflicting contracts, ungrounded answer); or compliant but above the approval amount, or not in TRY | Approver approves (on to the portal) or rejects; never their own upload |
| `RPA_FAILED`          | The portal kept failing; the command was dead-lettered                                                    | Admin reprocesses it with a new command |

`POSTED` and `REJECTED` are final; every transition, with actor and reason, is kept in an append-only history. See
[`docs/DECISIONS.md` §4.1](docs/DECISIONS.md) for the full transition table.

### How the guarantees are implemented

- **Transactional outbox** (`common-messaging`): business data and the outgoing message are written in one
  transaction; a relay publishes with publisher confirms and `mandatory`, and marks a row as published only after the
  broker's ack.
- **Idempotent consumers**: the `messageId` is recorded in an `inbox` table in the same transaction as the work, so a
  redelivered message has no second effect.
- **Conditional state transitions**: `UPDATE … WHERE status = ? AND version = ?`; late or out-of-order events change
  nothing and are recorded.
- **Delivery limit + DLQ per queue**: quorum queues with `x-delivery-limit = 3`. Listeners use manual ack and an
  explicit `basic.reject`, because in RabbitMQ 4.x only `reject` (not `nack`) increments the delivery counter — with
  Spring AMQP's default, a failing message would never reach the DLQ (ADR-19). Reprocessing is a state transition
  that writes a new command, never a move from the DLQ back to the queue (ADR-18).
- **At most once at the portal**: a single active consumer on the RPA queue (a second instance waits on standby);
  a per-invoice Redis lock, taken before the portal is touched (if the lock is taken or Redis is down, the message
  goes to a 30-second waiting room instead of entering); and a **portal search before every entry** — if
  `rpa-service` dies after the portal saved the invoice, the retried message finds the record and does not enter it
  again. Temporary portal errors and expired sessions are retried through the waiting room (re-login, at most 4
  attempts, a screenshot of every failed step), then dead-lettered.
- **Guarding the LLM**: fixed context window and output limit, length limits in the JSON schema, bounded retries with
  the parse error fed back; output that hits a limit is treated as unreliable. The LLM returns values *as printed*;
  deterministic code converts Turkish numbers and dates.
- **Contract compliance without trusting the LLM**: the valid contract is chosen in SQL; for each item and for the
  payment term the matching clauses are found by vector search; the LLM only reads the value and quotes the clause;
  the quote must appear verbatim in the clause and the value in the quote; Java compares the numbers. An answer that
  fails these checks sends the invoice to an approver, never to the portal.
- **No secrets in images, code, tests or logs**: each container receives only the variables it needs from `.env`;
  tests generate random passwords; the smoke test scans all container logs for every password.

## Getting started

### Prerequisites

Tested on an Apple Silicon Mac (M3 Pro, 18 GB).

- **Docker Desktop** with at least **8 GB** of memory and **~15 GB of free disk** for the images and build cache.
- **Ollama** on the host, with the chat model (~4.7 GB) and the embedding model (~1.2 GB) pulled:
  ```bash
  brew install ollama
  ollama serve                      # keep it running
  ollama pull qwen2.5:7b-instruct
  ollama pull bge-m3
  ```
- No JDK is needed to *run* the system — images build inside Docker. To run the tests you need Java 25
  (`sdk env` with [SDKMAN!](https://sdkman.io) picks it up from `.sdkmanrc`).

### Run

```bash
cp .env.example .env              # then replace every "change-me" with a password of your own
docker compose --profile all up -d --build --wait
```

The first build takes several minutes: it downloads ~5 GB of base images (the Playwright image with Chromium alone
is ~3.8 GB) and all Maven dependencies. Later builds are cached. Infrastructure only (for running services from the
IDE): `docker compose --profile infra up -d`.

### Demo

The smoke test uploads a sample invoice and follows it to the portal:

```bash
scripts/smoke-e2e.sh              # or: scripts/smoke-e2e.sh path/to/invoice.pdf
```

It checks prerequisites (both models pulled), brings the stack up, uploads the supplier's matching contract (contract
compliance is real; without a contract the invoice waits for approval), uploads the PDF, waits for `POSTED`, prints
the extracted fields, the confidence score, the compliance result, every state transition and the portal reference
number, and finally scans all container logs for secrets.

By hand:

```bash
# HTTP Basic; curl asks for the password (API_EXPERT_PASSWORD from .env)
curl -u expert -F "file=@extraction-service/src/test/resources/invoices/invoice-01.pdf" \
     http://localhost:8081/api/v1/documents
# 202 {"documentId":"…","status":"RECEIVED","duplicate":false}

curl -u expert http://localhost:8081/api/v1/documents/<documentId>          # status, fields, rule results, compliance, portal ref. no
curl -u expert http://localhost:8081/api/v1/documents/<documentId>/history  # every transition with actor and reason
curl -u expert "http://localhost:8081/api/v1/documents?status=POSTED"       # filtered, paged list
```

Uploading the same file again returns `200` with `"duplicate": true` and the existing record. Ten synthetic Turkish
invoices (including a deliberately inconsistent one and an image-only scan) are in
[`extraction-service/src/test/resources/invoices`](extraction-service/src/test/resources/invoices).

Supplier contracts go to `compliance-service` (same users); they are indexed clause by clause with `bge-m3`. Eight
synthetic contracts tied to the invoices — a price overrun, a different payment term, two overlapping contracts, an
expired one — are in [`compliance-service/src/test/resources/contracts`](compliance-service/src/test/resources/contracts).

```bash
curl -u expert -F "file=@compliance-service/src/test/resources/contracts/contract-01.pdf" \
     -F supplierVkn=4810293756 -F validFrom=2026-01-01 -F validTo=2026-12-31 \
     http://localhost:8082/api/v1/contracts
# 202 {"contractId":"…","status":"INGESTING","duplicate":false,"warnings":[]}
curl -u expert http://localhost:8082/api/v1/contracts/<contractId>   # READY + clause chunks
```

Every LLM attempt for an invoice (model, prompt version, raw answer, parse error) can be inspected on
`extraction-service`; raw answers are kept for 90 days, the attempt metadata stays:

```bash
curl -u expert "http://localhost:8083/api/v1/extraction-runs?documentId=<documentId>"
```

To see the bot's work, open the portal at <http://localhost:8090> and log in with `PORTAL_USERNAME` /
`PORTAL_PASSWORD` from your `.env`.

## Human steps and administration

All APIs use HTTP Basic with four users from configuration — `expert`, `approver`, `admin` and `expert-approver` —
whose passwords come from `.env` (`API_*_PASSWORD`). Rules that depend on the record are checked in the service: an
approver cannot approve an invoice they uploaded themselves (they can reject it).

| Role       | Endpoint (`document-service` unless noted)                                          | Purpose |
| ---------- | ----------------------------------------------------------------------------------- | ------- |
| any user   | `GET /api/v1/documents`, `…/{id}`, `…/{id}/history`                                  | List, detail (with `ETag`), status history |
| `EXPERT`   | `POST /api/v1/documents`                                                            | Upload |
| `EXPERT`   | `PUT /api/v1/documents/{id}/fields` with `If-Match`                                 | Correct a `NEEDS_REVIEW` record; the arithmetic rules re-check it (`422` with the violations) |
| `EXPERT`   | `POST /api/v1/documents/{id}/duplicate-decision`                                    | Close a duplicate suspicion; the matching records are in `duplicateOf` |
| `APPROVER` | `POST /api/v1/documents/{id}/approve`                                               | Approve a `PENDING_APPROVAL` record; the compliance findings (clause and quote) are in the detail |
| `EXPERT` / `APPROVER` | `POST /api/v1/documents/{id}/reject` with a reason                       | Reject from `NEEDS_REVIEW` (expert) or `PENDING_APPROVAL` (approver) |
| `ADMIN`    | `GET /api/v1/admin/dead-letters`, `POST …/{id}/reprocess`, `POST …/{id}/ignore`      | Parked dead letters; reprocess an `RPA_FAILED` invoice |
| `ADMIN`    | `GET` / `PUT /api/v1/admin/settings`                                                | Confidence threshold (default 0.80) and approval amount (default 100,000 TRY), with an audit trail |
| `EXPERT`   | `POST /api/v1/contracts` on `compliance-service`                                    | Upload a supplier contract (reads: any user) |
| `EXPERT` / `ADMIN` | `GET /api/v1/extraction-runs` on `extraction-service`                       | Raw LLM answers |

**Metrics** are in Prometheus format on every service (processing time and outcome per queue, retries, dead letters,
outbox backlog, LLM permit wait, stuck records): `curl -u admin http://localhost:8081/actuator/prometheus` (also 8082,
8083). Queue and DLQ depths come from RabbitMQ itself at <http://localhost:15692/metrics/per-object>. Alert rules, with
promtool tests, are in [`infra/prometheus`](infra/prometheus); there is no Prometheus server in Compose.

**RabbitMQ's management UI** is at <http://localhost:15672> (bound to localhost) if `RABBITMQ_ADMIN_PASSWORD` is set in
`.env`: a separate, read-only user (`monitoring` tag, no write permission) for looking at queues, DLQs and messages.
The services' own user cannot log in there. Note that in RabbitMQ the read permission also allows getting messages
from and purging a queue.

## Tests

| What | Command | Needs |
| --- | --- | --- |
| Unit + integration tests (Testcontainers: real RabbitMQ topology, Postgres, Redis; stub LLM and embeddings) | `./mvnw verify` | Docker, Java 25 |
| System tests: whole stack via Docker Compose, fake Ollama, real crashes (`docker kill`) | `./mvnw -Psystem-tests -pl system-tests -am verify -Dtest='*SystemTest' -Dsurefire.failIfNoSpecifiedTests=false` | Docker |
| Extraction accuracy against the real model | `./mvnw -pl extraction-service -am verify -Dsmoke.ollama=true -Dtest=OllamaSmokeTest -Dsurefire.failIfNoSpecifiedTests=false` | Ollama |
| Compliance accuracy against the real models | `./mvnw -pl compliance-service -am verify -Dsmoke.ollama=true -Dtest=OllamaComplianceSmokeTest -Dsurefire.failIfNoSpecifiedTests=false` | Ollama |
| Performance (NFR-07) | `scripts/measure-nfr07.sh` | stack + Ollama |

The system tests cover the cross-service scenarios: the synthetic set end to end, duplicate upload, a second document
for the same invoice (no second portal entry after the expert's decision), contract compliance through the real
pipeline, an LLM that keeps returning invalid JSON, the same message published twice, a poison message, an invoice
the portal always rejects (dead-lettered while the invoices behind it are still posted), and each of
`document-service`, `extraction-service` and `rpa-service` being killed mid-flight with no invoice left stuck —
including `rpa-service` killed after the portal saved the invoice (found by the pre-search, entered once).

## Measurements

Apple Silicon M3 Pro, 18 GB; `qwen2.5:7b-instruct` (4-bit) and `bge-m3` on Ollama 0.35.0.

| | Target | Measured |
| --- | --- | --- |
| Upload API latency | p95 < 500 ms | p95 **91 ms** (p50 73 ms) — BCrypt password check on every request; 16 ms before authentication |
| Single-page invoice, upload → `POSTED` | < 2 min | **32–52 s**, including the real compliance check (~15–25 s) |
| Extraction correct on the synthetic set | ≥ 8 of 10 without a human | **7 of 10** with the real model — see limitations |
| Compliance result correct on the synthetic set | — | **10 of 10** with the real models (price overrun and payment-term findings as expected) |
| Whole stack memory at peak | fits 18 GB | containers ~2.3 GB + Ollama ~4.9 GB (measured before the real compliance check) |

Details and history: [`docs/DECISIONS.md`](docs/DECISIONS.md) (B-29, B-30, B-31, B-46).

## Known limitations

- **Extraction accuracy is 7/10 on the synthetic set with the 7B model**, below the 8/10 target. The remaining error
  type is a number from the item description leaking into the quantity. Wrong extractions are caught by the
  validation rules and land in `NEEDS_REVIEW`; they never reach the portal.
- **Compliance on invoices with many lines is slow**: one LLM call per distinct item, ~3.3 min for a 48-line invoice.
- **Invoices from a supplier without a valid contract always wait for approval** (`NO_CONTRACT`).
- **Authentication is HTTP Basic with users from configuration.** It is meant to run behind TLS; adding a user or
  changing a password needs a restart.
- **During failover between two RPA instances**, both may briefly work on *different* invoices (never the same one).
- **The status history is append-only at the database level** (revoked privileges plus triggers). This protects
  against application bugs and accidents, not against a malicious database owner.
- **Re-running `scripts/smoke-e2e.sh` or `scripts/measure-nfr07.sh` on the same stack** uploads the same invoices
  again and hits the duplicate check; both scripts answer "not a duplicate" and the portal pre-search reuses the first
  portal reference.
- **Ollama keeps generating after the client gives up.** The output limit (`num_predict`) is therefore mandatory;
  without it one looping request can block the model for every invoice behind it.
- **Do not enable Playwright's `DEBUG=pw:api`** — it may log the portal password typed into the form.
- **If Docker's disk fills up, RabbitMQ can lose its metadata** (user, vhost, queues) while staying up; services then
  fail with `ACCESS_REFUSED`. Recover with `docker compose restart rabbitmq` (the topology is rebuilt from
  `infra/rabbitmq/definitions.json`); queued messages are lost, the databases are not affected. Keep ~10 GB free
  (`docker image prune -f`, `docker builder prune -f`).
- The mock portal keeps its records in memory; they are gone after a restart.

## Next steps

- **Extraction:** a larger model (`qwen2.5:14b-instruct`) and page-by-page extraction for long tables, measured on a
  bigger invoice set — tuning against ten invoices risks overfitting.
- **Compliance:** ask about items that fall into the same clause in one LLM call; hybrid retrieval (Postgres
  full-text + vector, Reciprocal Rank Fusion) if retrieval quality turns out to be insufficient on real contracts.
- **Operations:** a short-lived cache for authenticated users, an end-to-end system test for
  reprocessing after the portal recovers.
- **Out of scope by design:** OCR for scanned PDFs, e-Invoice (UBL) and tax authority integration, a UI or
  dashboards, multi-tenancy and SSO, cloud / Kubernetes deployment, currency conversion.

## Project layout

```
common-messaging/     message envelope and records, outbox + relay, inbox, cleanup jobs, listener with manual ack (auto-configured)
llm-support/          cross-service LLM semaphore (Redis, local fallback)
document-service/     orchestrator, state machine, REST API (upload, review, approval, admin), dead-letter handling
extraction-service/   PDF text, LLM client and prompts, Turkish parsers, validation rules, confidence score, LLM audit API
compliance-service/   contract upload and clause indexing (pgvector), contract compliance check
rpa-service/          Playwright portal client, invoice lock, waiting room, portal pre-search
mock-portal/          legacy portal stand-in (Thymeleaf) with fault injection
system-tests/         cross-service tests against the Compose stack
test-support/         shared Testcontainers setup (real RabbitMQ topology)
infra/                Postgres init script, RabbitMQ definitions and config, Prometheus scrape config and alerts
scripts/              smoke test, NFR-07 measurement
docs/                 decisions, requirements, system design (Turkish)
```

**Stack:** Java 25, Spring Boot 4.1, Spring AMQP, Spring Security, Spring AI 2.0 (Ollama), RabbitMQ 4 (quorum
queues), PostgreSQL 17 + pgvector, Redis + Redisson, PDFBox, Playwright for Java, Thymeleaf, Micrometer + Prometheus,
Testcontainers, Flyway, Maven.
