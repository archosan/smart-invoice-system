# Smart Invoice Processing and Contract Compliance System — Requirements and Scope

> **English translation.** The original is [`docs/Gereksinimler ve Kapsam.md`](../Gereksinimler%20ve%20Kapsam.md)
> (Turkish), which is authoritative; if the two differ, the Turkish version wins. Translated on 2026-10-03. This is
> the requirements document of 30 September 2026; later decisions and their current state are in
> [`DECISIONS.md`](DECISIONS.md).

## Purpose and context

The system reads supplier invoices from PDF, checks them against rules and against the relevant supplier contract, and enters approved invoices into a legacy accounting portal that has no API, using RPA. The goal is a backend system with a narrow scope but deep failure scenarios: every invoice is entered into the portal at most once (the v1 limitation is in the Scope section), every step is traceable, and work left half-done continues where it stopped.

| Actor                           | Type               | Role in the system                                                                      |
| ------------------------------- | ------------------ | --------------------------------------------------------------------------------------- |
| Accounting expert (AP expert)   | Human              | Uploads invoices and contracts, tracks status, corrects low-confidence records          |
| Approver                        | Human              | Approves or rejects non-compliant or above-threshold invoices                           |
| System administrator            | Human              | Inspects and reprocesses messages in the DLQs, manages supplier and threshold definitions |
| Legacy accounting portal (mock) | External system    | No API; it has login, session timeout, pagination and a form that occasionally fails    |
| Local LLM (Ollama)              | External component | Maps text to a JSON schema, interprets contract clauses; assumed to be fallible         |

Services: document-service, extraction-service, compliance-service, rpa-service and the mock portal. Technology: Spring Boot, RabbitMQ, Redis, PostgreSQL + pgvector, PDFBox, Spring AI + Ollama, Playwright (Java).

## User scenarios

There is one happy path and nine failure scenarios; the depth of the project comes from the failure scenarios. Every scenario should eventually become an integration test.

### US-01 Happy path: upload an invoice, have it entered into the portal

As an accounting expert I want to upload an invoice PDF so that it is processed into the portal without manual data entry.

1. The expert uploads the PDF; the system immediately returns a `documentId` and the status `RECEIVED`.
2. extraction-service extracts the text with PDFBox, has the LLM produce schema-constrained JSON, and validates it against rules.
3. compliance-service compares the invoice with the supplier's contract clauses and finds it compliant.
4. rpa-service logs into the portal, enters the invoice, and stores the record number the portal gives.
5. The expert sees the status `POSTED` and the portal record number.

### Failure and exception scenarios

| ID    | Scenario                                                    | Trigger                                                                                                   | Expected system behaviour |
| ----- | ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ------------------------- |
| US-02 | The same PDF is uploaded a second time                      | The file hash has been seen before                                                                        | No new record is created; the existing `documentId` and its status are returned (HTTP 200, `duplicate: true`) |
| US-03 | Different file, same invoice                                | Different hash, but the same supplier tax number (VKN) + invoice no                                       | The record becomes `DUPLICATE_SUSPECTED`, is not entered into the portal, and is shown to the expert |
| US-04 | The LLM extracts a wrong field                              | Sum of lines + VAT ≠ grand total, invalid tax number format, etc.                                         | The confidence score drops below the threshold and the record becomes `NEEDS_REVIEW`; the expert corrects the fields and approves |
| US-05 | The LLM gives output that does not follow the schema, or no answer | Parse error, timeout                                                                               | Retries a limited number of times; when exhausted, `NEEDS_REVIEW` |
| US-06 | Mismatch with the contract                                  | Unit price higher than in the contract, different payment term, contract expired                          | The record becomes `PENDING_APPROVAL`; the approver sees the relevant contract clause and the difference, and approves or rejects |
| US-07 | No valid contract on the invoice date, or more than one     | No contract matches the tax number + invoice date, or two contracts with overlapping date ranges match    | The compliance check is skipped and the record goes to human approval |
| US-08 | The RPA bot crashes during entry                            | The form is half-done, the service restarts                                                               | The bot first searches the portal by invoice no; if the record exists it takes the record number and sets `POSTED`, otherwise it enters it again. A duplicate record is never created |
| US-09 | The portal returns a temporary error                        | Session timeout, a 500 error, element not found                                                           | Retry with increasing waits; re-login if the session drops; when exhausted the message goes to the DLQ and the record becomes `RPA_FAILED` |
| US-10 | The administrator handles the DLQ                           | The portal has recovered                                                                                  | The administrator inspects the message in the DLQ and requeues it; thanks to the check in US-08, reprocessing is safe |

### Supporting scenarios

- US-11: As an expert I want to upload a supplier contract PDF with its start and end dates so that later invoices are compared with this contract.
- US-12: As an expert I want to see the full history of an invoice (who, when, from which state to which) so that I can explain it in an audit.
- US-13: As an approver I want to list pending invoices and write a rejection reason.

## Functional requirements

Requirements are numbered per service; the Priority column separates the MVP (v1) from the second iteration (v2).

### document-service

| ID     | Requirement | Priority |
| ------ | ----------- | -------- |
| FR-D1  | The system accepts a PDF invoice upload, stores the file and returns a `documentId`; processing starts asynchronously | v1 |
| FR-D2  | The system computes the file's SHA-256 hash; if the same hash arrives again it does not create a new record but returns the existing one | v1 |
| FR-D3  | The system manages each invoice's status with a state machine: `RECEIVED → EXTRACTED → VALIDATED → COMPLIANCE_CHECKED → QUEUED_FOR_RPA → POSTED`; the side branches are `NEEDS_REVIEW`, `PENDING_APPROVAL`, `REJECTED`, `DUPLICATE_SUSPECTED`, `RPA_FAILED` | v1 |
| FR-D4  | The system only allows the transitions in the state transition table below and rejects the others (e.g. no way back from `POSTED`) | v1 |
| FR-D5  | The system offers a status query and list API (filters by status, supplier, date; pagination) | v1 |
| FR-D6  | The system writes every state transition, with actor, time and reason, to an immutable audit log | v2 |
| FR-D7  | The system offers an API for field correction on `NEEDS_REVIEW` records and approval/rejection (reason mandatory) on `PENDING_APPROVAL` records | v2 |
| FR-D8  | The system flags a content-based duplicate suspicion by matching supplier tax number + invoice no | v2 |
| FR-D9  | document-service is the sole owner of state: it consumes the other services' events, applies the transition, makes the decision including the duplicate check and the priority rule, and publishes the message for the next step | v1 |
| FR-D10 | document-service listens to the DLQs and updates the related record according to the state transition table (ExtractInvoice → NEEDS_REVIEW, CheckCompliance → PENDING_APPROVAL, PostToPortal → RPA_FAILED). The DLQ of the events document-service consumes itself (ExtractionCompleted etc.) cannot update the record; for these DLQs a depth metric and an alert are defined. In v1 the messages in the DLQ are only inspected, not requeued; reprocessing comes in v2 with FR-A1, through document-service | v1 |
| FR-D11 | document-service compares the grand total with the amount threshold configured through FR-A2; an invoice above the threshold becomes PENDING_APPROVAL even if it complies with the contract | v2 |

#### State transition table

document-service is the sole owner of state: the other services only publish events, and document-service applies every transition. The "Produced by" column is the source of the event that triggers the transition. The side branches return to the main flow at a single point; `POSTED` and `REJECTED` are final states.

**Priority rule:** when `ExtractionCompleted` is received, document-service evaluates `NEEDS_REVIEW` > `DUPLICATE_SUSPECTED` > `VALIDATED` in that order. If the confidence score is low, the tax number and invoice no may be unreliable too, so the duplicate check is not done; when the expert corrects the record it goes back to `EXTRACTED` and the check runs then.

| Source                | Target                | Triggering event                                                                              | Produced by              | Version |
| --------------------- | --------------------- | --------------------------------------------------------------------------------------------- | ------------------------ | ------- |
| —                     | `RECEIVED`            | PDF uploaded, new hash                                                                        | Expert (API)             | v1      |
| `RECEIVED`            | `EXTRACTED`           | `ExtractionCompleted`: fields, confidence score, rule results                                 | extraction-service       | v1      |
| `RECEIVED`            | `NEEDS_REVIEW`        | `ExtractionFailed` (no text, LLM attempts exhausted) or `ExtractInvoice` fell into the DLQ    | extraction-service / DLQ | v1      |
| `EXTRACTED`           | `NEEDS_REVIEW`        | Confidence score < threshold (priority 1)                                                     | document-service         | v1      |
| `EXTRACTED`           | `DUPLICATE_SUSPECTED` | Tax number + invoice no match another record (priority 2)                                     | document-service         | v2      |
| `EXTRACTED`           | `VALIDATED`           | Confidence score ≥ threshold, no duplicate suspicion                                          | document-service         | v1      |
| `NEEDS_REVIEW`        | `EXTRACTED`           | The expert corrected the fields; the decision is made again                                   | Expert (API)             | v2      |
| `NEEDS_REVIEW`        | `REJECTED`            | The expert rejected (reason mandatory)                                                        | Expert (API)             | v2      |
| `DUPLICATE_SUSPECTED` | `VALIDATED`           | The expert confirmed it is not a duplicate                                                    | Expert (API)             | v2      |
| `DUPLICATE_SUSPECTED` | `REJECTED`            | The expert confirmed it is a duplicate                                                        | Expert (API)             | v2      |
| `VALIDATED`           | `COMPLIANCE_CHECKED`  | `ComplianceCompleted`: compliant and amount ≤ threshold (in v1 the stub always returns "compliant") | compliance-service | v1      |
| `VALIDATED`           | `PENDING_APPROVAL`    | `ComplianceCompleted`: non-compliant, no valid contract / conflicting contracts, or amount > threshold (FR-D11) | compliance-service | v2 |
| `VALIDATED`           | `PENDING_APPROVAL`    | `CheckCompliance` fell into the DLQ; the approver sees that the compliance check could not be done | DLQ                 | v1      |
| `PENDING_APPROVAL`    | `COMPLIANCE_CHECKED`  | The approver approved                                                                         | Approver (API)           | v2      |
| `PENDING_APPROVAL`    | `REJECTED`            | The approver rejected (reason mandatory)                                                      | Approver (API)           | v2      |
| `COMPLIANCE_CHECKED`  | `QUEUED_FOR_RPA`      | The state and the `PostToPortal` command were written to the outbox in the same transaction   | document-service         | v1      |
| `QUEUED_FOR_RPA`      | `POSTED`              | `RpaCompleted`: portal record number (including a record found by the pre-search)            | rpa-service              | v1      |
| `QUEUED_FOR_RPA`      | `RPA_FAILED`          | `PostToPortal` exceeded the delivery limit and is in the DLQ                                  | DLQ                      | v1      |
| `RPA_FAILED`          | `QUEUED_FOR_RPA`      | The administrator reprocessed it through FR-A1; document-service publishes a new `PostToPortal` | Administrator (API)    | v2      |

Moving a message straight from the DLQ back to the queue is not a valid transition: an `RpaCompleted` arriving while the record is `RPA_FAILED` is rejected, and the system and the portal get out of sync. That is why reprocessing is done only through FR-A1.

#### Message contracts

Message names are fixed here; the other sections use these names. Every message carries a `messageId` and a `documentId` (= `correlationId`).

| Name                  | Kind    | Producer → Consumer   | Main content                                       | Version |
| --------------------- | ------- | --------------------- | -------------------------------------------------- | ------- |
| `ExtractInvoice`      | Command | document → extraction | File location                                      | v1      |
| `ExtractionCompleted` | Event   | extraction → document | Fields, confidence score, rule results             | v1      |
| `ExtractionFailed`    | Event   | extraction → document | Failure reason (no text, LLM attempts exhausted)   | v1      |
| `CheckCompliance`     | Command | document → compliance | Tax number, invoice date, lines, amounts           | v1      |
| `ComplianceCompleted` | Event   | compliance → document | Compliance result, findings, the clauses they rely on | v1   |
| `PostToPortal`        | Command | document → rpa        | Portal form fields                                 | v1      |
| `RpaCompleted`        | Event   | rpa → document        | Portal record number, whether found by the pre-search | v1   |

### extraction-service

| ID    | Requirement | Priority |
| ----- | ----------- | -------- |
| FR-E1 | The service extracts the text from the PDF deterministically with PDFBox; if no text comes out (scanned PDF), it publishes an `ExtractionFailed` event | v1 |
| FR-E2 | The service has the LLM map the text to a fixed JSON schema: supplier name, tax number, invoice no, date, due date, lines (description, quantity, unit price, VAT rate), subtotal, VAT, grand total, currency | v1 |
| FR-E3 | The service validates the output against rules: sum of lines = subtotal, subtotal + VAT = grand total (within tolerance), tax number has 10 digits, date ≤ due date, mandatory fields present | v1 |
| FR-E4 | The service computes a confidence score from the rule results and writes it into the `ExtractionCompleted` event; document-service does the comparison with the threshold (the threshold is read there through FR-A2) | v1 |
| FR-E5 | LLM access is behind the Spring AI abstraction; the model name changes through configuration | v1 |
| FR-E6 | The service stores the raw LLM output together with the model and prompt version used | v2 |

### compliance-service

| ID    | Requirement | Priority |
| ----- | ----------- | -------- |
| FR-C1 | The service accepts a supplier contract PDF, splits it clause by clause, and writes the embeddings to pgvector with the supplier tax number, contract ID and validity date range; a supplier may have more than one contract stored | v2 |
| FR-C2 | The service first selects the contract valid on the invoice date (tax number + date range filter), then retrieves the relevant clauses only from that contract by vector similarity | v2 |
| FR-C3 | The service checks the unit price, the payment term and the contract's validity date; for every finding it returns the text of the clause it relies on | v2 |
| FR-C4 | The service writes the result into the `ComplianceCompleted` event: the compliance result (compliant, non-compliant, no valid contract, contract conflict) and the clause each finding relies on. document-service makes the `PENDING_APPROVAL` decision | v2 |
| FR-C5 | In v1 the service and its queue run as a stub: it consumes the `CheckCompliance` command and publishes a `ComplianceCompleted` event with a "compliant" result for every invoice. In v2 only the check logic is added; the message contract and the flow do not change | v1 |

### rpa-service

| ID    | Requirement | Priority |
| ----- | ----------- | -------- |
| FR-R1 | The service logs into the portal with Playwright and enters the approved invoice that comes from the queue | v1 |
| FR-R2 | After the entry the service reads the record number the portal gives and reports it to document-service | v1 |
| FR-R3 | Before every entry the service searches the portal by invoice no (going through the pagination); if the record exists, it does not enter it again | v2 |
| FR-R4 | The service detects a session timeout and logs in again | v2 |
| FR-R5 | On temporary errors the service retries a limited number of times with increasing waits; when exhausted it leaves the message to the DLQ | v2 |
| FR-R6 | The service stores a screenshot and the failing step for every failed attempt | v2 |
| FR-R7 | Before the entry the service takes a per-invoice Redis distributed lock (with a TTL, renewed while the work runs); if the lock cannot be taken it puts the message back on the queue with a delay. The pre-search (FR-R3) is done after the lock is taken | v2 |
| FR-R8 | In v1 the RPA queue is consumed by a single consumer (concurrency 1, prefetch 1); so the same invoice cannot be processed twice at the same time | v1 |

### Mock legacy portal

| ID    | Requirement | Priority |
| ----- | ----------- | -------- |
| FR-P1 | The portal offers login, an invoice entry form and a paged invoice list/search page; it offers no API | v1 |
| FR-P2 | The portal shows a record number on a successful entry | v1 |
| FR-P3 | The portal does configurable fault injection: session timeout, random 500s, late-loading elements | v2 |

### Administration

| ID    | Requirement | Priority |
| ----- | ----------- | -------- |
| FR-A1 | The administrator lists the messages in the DLQs and requeues them | v2 |
| FR-A2 | The administrator changes the confidence score threshold and the amount threshold that requires approval through configuration | v2 |

## Non-functional requirements

The two most critical guarantees: an invoice is entered into the portal at most once, and no message is ever silently lost. The numeric targets were chosen for a single developer and an M3 Pro with 18 GB, and should be updated after real measurements.

| ID     | Category        | Requirement | How it is verified | Priority |
| ------ | --------------- | ----------- | ------------------ | -------- |
| NFR-01 | Idempotency     | The same invoice is entered into the portal at most once. Three layers: (1) consumers keep the IDs of processed messages, so if the same message arrives twice the outcome does not change; (2) before the entry, RPA takes a per-invoice Redis distributed lock (TTL longer than the longest RPA run, renewed while the work runs), so two workers cannot process the same invoice at the same time; (3) after the lock is taken, a pre-search is done in the portal (FR-R3) | A test that publishes the same message twice; a test that gives the same invoice to two workers at the same time; a test that kills RPA during entry | v1: (1) + a single RPA consumer; v2: (2) and (3) |
| NFR-02 | Reliability     | Messages are in durable queues and consumed with manual ack; the outbox pattern is used between the DB write and the message publish: the service writes the state change and the outgoing message in the same transaction, and a scheduled publisher scans the outbox table and publishes (at-least-once delivery; duplicates are handled by the first layer of NFR-01). This rule applies to every publish of every service | A test that kills the service at publish time; no message may be lost | v1 |
| NFR-03 | Fault tolerance | Every queue has a dead-letter exchange and a delivery limit (quorum queue + x-delivery-limit, e.g. 3); a message over the limit lands in the DLQ without being requeued, so a single broken message cannot lock the queue (head-of-line blocking). Temporary errors are handled with retries with increasing waits; every message that falls into the DLQ is visible and can be reprocessed | Tested with the mock portal's fault injection | v1: DLX + delivery limit; v2: retries with increasing waits |
| NFR-04 | Recoverability  | When any service restarts, work left half-done continues from the state machine where it stopped | A test that kills the container during an RPA entry (US-08) | v2 |
| NFR-05 | Auditability    | Every state transition, LLM output and approval decision is traceable; audit records are never updated or deleted | Rebuilding an invoice's history with a single query | v2 |
| NFR-06 | Observability   | Structured (JSON) logs, a `correlationId` (= `documentId`) carried through all services, Actuator + Micrometer metrics (queue depth, processing time, number of retries and DLQ messages) | Finding one invoice's logs across all services by its ID | v1: logs + correlationId; v2: metrics |
| NFR-07 | Performance     | The upload API responds within 500 ms (processing is asynchronous); for a single-page invoice, upload → `POSTED` takes under 2 minutes on the local machine | A simple load test and metrics | v1 |
| NFR-08 | Resource limit  | The whole stack (infrastructure in Docker + services + Ollama on macOS) runs in 18 GB; the LLM is a 7–8B class, 4-bit quantized model; the number of concurrent requests to the LLM is limited to 1–2 | Memory measurement with the full stack up | v1 |
| NFR-09 | Changeability   | The LLM, the embedding model and the portal selectors are in configuration; changing the model needs no code change | Changing the model and running the tests | v1 |
| NFR-10 | Security        | Portal credentials are read from environment variables/secrets and never written to code or logs; the API has role-based authorization (expert, approver, administrator); an approver cannot approve an invoice they uploaded themselves | Log scan, authorization tests | v1: secret management; v2: role-based authorization |
| NFR-11 | Data privacy    | Invoice and contract data never leave the machine; the LLM runs locally | By architecture | v1 |
| NFR-12 | Testability     | Integration tests with RabbitMQ/Postgres/Redis through Testcontainers; the LLM can be replaced with a fake (stub) client in tests | Tests passing in CI | v1 |
| NFR-13 | Installation    | Comes up with a single command (`docker compose up`); the README explains installation, architecture and demo steps | An installation trial on a clean machine | v1 |
| NFR-14 | Language        | The application processes Turkish invoices correctly (Turkish characters, TL, decimal comma); the embedding model is multilingual (e.g. bge-m3) | Tests with a set of Turkish sample invoices | v1 |

## Scope

v1 is the end-to-end happy path and the first version to go on the CV (about 3–4 weeks at side-project pace); v2 brings reliability and the compliance check. The out-of-scope items are listed in the README as "next steps".

### v1 — End-to-end happy path

- Scenarios in scope: US-01, US-02, US-04 (rule validation and confidence score; without a correction screen, only the `NEEDS_REVIEW` status), US-05.
- Requirements: FR-D1–D5, FR-D9–D10, FR-E1–E5, FR-C5, FR-R1–R2, FR-R8, FR-P1–P2 and the items marked v1 in the NFR table.
- Infrastructure: docker compose (RabbitMQ, Redis, Postgres), Ollama on macOS, a basic README and an architecture diagram.

**Known limitation (v1):** Since the pre-search in the portal (FR-R3) arrives in v2, if RPA crashes during entry and the message is processed again, a duplicate record can be created. Since a single RPA consumer runs in v1, there is no risk of concurrent double entry; the "at most once" guarantee is completed in v2 with the lock + pre-search.

### v2 — Reliability and compliance

- Scenarios in scope: US-03, US-06 – US-13.
- Requirements: FR-D6–D8, FR-E6, FR-C1–C4, FR-D11, FR-R3–R7, FR-P3, FR-A1–A2.
- The items marked v2 in the NFR table; in particular the lock and pre-search layers of NFR-01, the retry part of NFR-03 and NFR-04 – NFR-05 are the main subject of this iteration.

### Out of scope

- OCR for scanned PDFs (if no text comes out, the record goes to human review).
- e-Invoice/UBL XML integration and a connection to GİB (the Turkish tax authority).
- Reporting, dashboards and a front end (API + Swagger and a simple status page are enough).
- Multi-tenancy, SSO/LDAP, integration with a real accounting system.
- Cloud deployment and Kubernetes.
- Multi-currency conversion (the invoice currency is stored, no exchange-rate conversion is done).

### Assumptions

- Invoices are text-based PDFs, one invoice = one file, mostly Turkish and in TL.
- A supplier is identified by the tax number (VKN) on the invoice; a supplier may have more than one contract with different validity dates, and the contract valid on the invoice date applies.
- The portal can be searched by invoice no; RPA's duplicate prevention relies on this search.
- Test data consists of synthetic invoice and contract PDFs; no real company data is used.

### v1 acceptance criteria

- [ ] At least 8 of the 10 synthetic invoices become `POSTED` without human intervention.
- [ ] A second upload of the same PDF creates no new record and no portal entry.
- [ ] An invoice whose totals do not add up falls into `NEEDS_REVIEW` and is not entered into the portal.
- [ ] The system comes up on a clean machine with `docker compose up` + Ollama.
- [ ] The README has an architecture diagram, demo steps and a v2 roadmap.
- [ ] An invoice that keeps failing in the portal falls into the DLQ after the delivery limit and does not block the processing of the invoices behind it.
- [ ] When a service killed while publishing a message restarts, no invoice is left stuck in an intermediate state.
