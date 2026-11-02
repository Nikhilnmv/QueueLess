# Diagrams

Editable Mermaid sources live next to this file (`*.mmd`). GitHub renders the blocks below.
To regenerate this page after editing a source, copy the file content into its block.

## High-level system architecture

Browser, Spring Boot modular monolith and PostgreSQL. One origin serves the React build, the REST API and the SSE streams.

Source: [`01-system-architecture.mmd`](01-system-architecture.mmd)

```mermaid
flowchart TB
    subgraph Browser["Browser (React + TypeScript SPA)"]
        Customer["Customer screens<br/>discover, book, ticket, notifications"]
        Staff["Staff dashboard<br/>live board, call next, serve"]
        Admin["Admin dashboard<br/>queues, schedule, members"]
        ES["EventSource (SSE client)"]
    end

    subgraph App["Spring Boot 3.5 modular monolith (single JVM, single origin)"]
        Web["Static files + SPA fallback<br/>(React build in classpath:/static)"]
        Sec["Spring Security<br/>session cookie, CSRF, URL rules"]
        subgraph Modules["Feature modules"]
            Auth["auth"]
            Org["organization"]
            Mem["membership<br/>AccessControlService"]
            Q["queue<br/>QueueLockService"]
            Appt["appointment"]
            Tkt["ticket<br/>TokenAllocator, QueueOperations"]
            Notif["notification"]
            RT["realtime<br/>SseEmitterRegistry"]
        end
        Common["common<br/>errors, time, config"]
    end

    DB[("PostgreSQL (Neon)<br/>source of truth<br/>Flyway-managed schema")]

    Customer -- "HTTPS REST /api" --> Sec
    Staff -- "HTTPS REST /api" --> Sec
    Admin -- "HTTPS REST /api" --> Sec
    ES -- "GET /api/queues/{id}/events<br/>GET /api/me/events" --> Sec
    Browser -- "GET / and app routes" --> Web
    Sec --> Modules
    Modules --> Common
    Tkt -- "domain events" --> RT
    Appt -- "domain events" --> RT
    Q -- "domain events" --> RT
    Notif -- "domain events" --> RT
    RT -. "SSE push after commit" .-> ES
    Modules -- "JPA / JDBC (HikariCP)" --> DB
    Sec -- "Spring Session JDBC" --> DB
```

## Database entity-relationship diagram

Tables created by the Flyway migrations V1-V3 (Spring Session tables from V4 omitted). See database-design.md for every constraint and index.

Source: [`02-entity-relationship.mmd`](02-entity-relationship.mmd)

```mermaid
erDiagram
    USERS ||--o{ ORGANIZATION_MEMBERSHIPS : "has"
    ORGANIZATIONS ||--o{ ORGANIZATION_MEMBERSHIPS : "grants"
    ORGANIZATIONS ||--o{ SERVICE_QUEUES : "runs"
    SERVICE_QUEUES ||--o{ APPOINTMENTS : "books"
    USERS ||--o{ APPOINTMENTS : "makes"
    SERVICE_QUEUES ||--o{ QUEUE_TICKETS : "issues"
    USERS |o--o{ QUEUE_TICKETS : "holds (null for guests)"
    APPOINTMENTS |o--o| QUEUE_TICKETS : "reserves exactly one"
    USERS ||--o{ NOTIFICATIONS : "receives"
    QUEUE_TICKETS |o--o{ NOTIFICATIONS : "about"

    USERS {
        uuid id PK
        varchar email UK "normalized lower-case"
        varchar full_name
        varchar password_hash "bcrypt, never returned"
        boolean enabled
        boolean platform_admin
        timestamptz created_at
        timestamptz updated_at
    }
    ORGANIZATIONS {
        uuid id PK
        varchar name
        varchar slug UK
        varchar description
        varchar timezone "IANA zone, fixed"
        boolean active
        timestamptz created_at
        timestamptz updated_at
    }
    ORGANIZATION_MEMBERSHIPS {
        uuid id PK
        uuid organization_id FK
        uuid user_id FK
        varchar role "CUSTOMER STAFF ADMIN"
        timestamptz created_at
        timestamptz updated_at
    }
    SERVICE_QUEUES {
        uuid id PK
        uuid organization_id FK
        varchar name "unique per organization"
        varchar token_prefix
        varchar status "OPEN PAUSED CLOSED"
        boolean booking_enabled
        time opening_time
        time closing_time
        int slot_duration_minutes
        int capacity_per_slot
        int default_service_minutes
        bigint state_version
    }
    APPOINTMENTS {
        uuid id PK
        uuid queue_id FK
        uuid user_id FK
        timestamptz scheduled_at
        date service_date
        varchar booking_reference UK
        varchar status
        uuid idempotency_key "unique per user"
        timestamptz cancelled_at
        uuid cancelled_by_user_id FK
    }
    QUEUE_TICKETS {
        uuid id PK
        uuid queue_id FK
        uuid user_id FK
        varchar guest_name
        uuid appointment_id FK "unique, same queue"
        varchar source
        date service_date
        int token_number "unique per queue and date"
        varchar status
        varchar priority
        smallint priority_rank "generated"
        timestamptz checked_in_at
        timestamptz called_at
        timestamptz service_started_at
        timestamptz completed_at
        timestamptz approach_notified_at
    }
    NOTIFICATIONS {
        uuid id PK
        uuid user_id FK
        uuid ticket_id FK
        varchar type
        varchar title
        varchar message
        timestamptz read_at
        timestamptz created_at
    }
```

## Appointment booking

Capacity check, insert and token reservation happen inside one transaction that holds the queue row lock.

Source: [`03-appointment-booking-sequence.mmd`](03-appointment-booking-sequence.mmd)

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer (browser)
    participant API as AppointmentController
    participant S as AppointmentService
    participant L as QueueLockService
    participant T as TicketService and TokenAllocator
    participant DB as PostgreSQL
    participant R as RealtimeEventRelay

    C->>API: POST /api/queues/{id}/appointments<br/>{scheduledAt} + Idempotency-Key + X-XSRF-TOKEN
    API->>S: book(queueId, userId, scheduledAt, key)
    S->>DB: BEGIN
    S->>L: lock(queueId)
    L->>DB: SELECT ... FROM service_queues WHERE id = ? FOR UPDATE
    Note over L,DB: concurrent bookings of this queue wait here
    S->>DB: same Idempotency-Key used before by this user?
    alt key already used
        S-->>API: existing booking (replayed)
        API-->>C: 200 OK + Idempotent-Replayed: true
    else new request
        S->>S: validate slot (future, within 14 days, on the slot grid, org time zone)
        S->>DB: COUNT bookings in slot WHERE status <> CANCELLED
        alt count >= capacity
            S-->>API: SLOT_FULL
            API-->>C: 409 problem detail
        else place available
            S->>DB: INSERT appointment (BOOKED, reference QL-XXXXXX)
            S->>T: reserveForAppointment(lockedQueue, ...)
            T->>DB: SELECT MAX(token_number) for queue and date
            T->>DB: INSERT queue_ticket (BOOKED, token = max + 1)
            S->>DB: INSERT notification APPOINTMENT_CONFIRMED
            S->>DB: UPDATE service_queues SET state_version = state_version + 1
            S->>DB: COMMIT (releases the lock)
            DB-->>R: after commit: QueueChangedEvent
            R-->>C: SSE queue-changed to subscribers (slots refresh)
            API-->>C: 201 Created {appointment, token}
        end
    end
```

## Concurrent token generation

Two walk-ins arriving at the same instant are serialized by the queue row lock and receive consecutive tokens.

Source: [`04-token-generation-sequence.mmd`](04-token-generation-sequence.mmd)

```mermaid
sequenceDiagram
    autonumber
    participant A as Request A (walk-in)
    participant B as Request B (walk-in)
    participant DB as PostgreSQL

    par both requests arrive at the same moment
        A->>DB: BEGIN
        A->>DB: SELECT ... FROM service_queues WHERE id = Q FOR UPDATE
        DB-->>A: row lock granted
    and
        B->>DB: BEGIN
        B->>DB: SELECT ... FROM service_queues WHERE id = Q FOR UPDATE
        Note over B,DB: B blocks until A commits (lock_timeout 5 s)
    end
    A->>DB: SELECT COALESCE(MAX(token_number), 0) WHERE queue = Q AND date = D
    DB-->>A: 6
    A->>DB: INSERT ticket token 7
    A->>DB: UPDATE service_queues SET state_version = state_version + 1
    A->>DB: COMMIT
    DB-->>B: row lock granted
    Note over B,DB: READ COMMITTED: the next statement sees A's committed ticket
    B->>DB: SELECT COALESCE(MAX(token_number), 0) WHERE queue = Q AND date = D
    DB-->>B: 7
    B->>DB: INSERT ticket token 8
    B->>DB: COMMIT
    Note over A,DB: UNIQUE (queue_id, service_date, token_number) is the final safeguard:<br/>a duplicate token would be rejected, never stored
```

## Staff call-next

Authorization against the queue's organization, then the locked transaction that calls exactly one ticket.

Source: [`05-call-next-sequence.mmd`](05-call-next-sequence.mmd)

```mermaid
sequenceDiagram
    autonumber
    actor S as Staff (browser)
    participant API as StaffQueueController
    participant O as QueueOperationsService
    participant AC as AccessControlService
    participant DB as PostgreSQL
    participant N as NotificationService
    participant R as RealtimeEventRelay
    actor C as Called customer

    S->>API: POST /api/staff/queues/{id}/call-next
    API->>O: callNext(queueId, staffId)
    O->>DB: organization of queue (scalar query)
    O->>AC: require(staffId, organizationId, OPERATE_QUEUE)
    AC->>DB: SELECT role FROM organization_memberships
    alt not STAFF or ADMIN of this organization
        AC-->>API: ACCESS_DENIED
        API-->>S: 403
    else authorized
        O->>DB: BEGIN, SELECT ... FROM service_queues FOR UPDATE
        O->>O: queue must be OPEN
        O->>DB: any ticket CALLED or SERVING today?
        alt counter busy
            O-->>API: ACTIVE_TICKET_EXISTS
            API-->>S: 409
        else counter free
            O->>DB: first WAITING ticket ORDER BY priority_rank, checked_in_at, created_at, id LIMIT 1
            O->>DB: UPDATE ticket SET status = CALLED, called_at = now (flush)
            Note over O,DB: partial unique index allows only one CALLED or SERVING per queue and day
            O->>N: TURN_CALLED notification (same transaction)
            O->>DB: TURN_APPROACHING for the new top 3 (once per ticket)
            O->>DB: state_version + 1, COMMIT
            DB-->>R: after commit
            R-->>S: SSE queue-changed (board refetches)
            R-->>C: SSE notification on personal stream
            API-->>S: 200 called ticket
        end
    end
```

## Live queue update (SSE)

Events are published only after commit and only carry ids; browsers refetch authorized snapshots.

Source: [`06-live-update-sequence.mmd`](06-live-update-sequence.mmd)

```mermaid
sequenceDiagram
    autonumber
    actor C as Customer browser
    participant ES as EventSource hook
    participant REG as SseEmitterRegistry
    participant SVC as Any queue write (transaction)
    participant REL as RealtimeEventRelay
    participant API as REST API

    C->>ES: open ticket page
    ES->>REG: GET /api/queues/{id}/events (session cookie)
    REG-->>ES: event ready {queueId, version}
    ES->>API: refetch snapshot (ticket, live summary)
    loop every 20 s
        REG-->>ES: comment heartbeat (detects dead connections)
    end
    SVC->>SVC: change queue and publish QueueChangedEvent (inside transaction)
    alt transaction commits
        SVC-->>REL: AFTER_COMMIT
        REL->>REG: broadcast to queue subscribers
        REG-->>ES: event queue-changed {queueId, version, reason}
        ES->>API: invalidate cached queries and refetch
        API-->>C: new position and estimate from PostgreSQL
    else transaction rolls back
        Note over SVC,REL: nothing is sent
    end
    Note over ES,REG: network drop: the browser reconnects, server restart: backoff 1 s to 30 s.<br/>Every reconnect receives ready again, so the snapshot is refetched and missed events do not matter.
```

## Deployment architecture

GitHub, CI, Render free web service (Docker) and Neon free PostgreSQL.

Source: [`07-deployment-architecture.mmd`](07-deployment-architecture.mmd)

```mermaid
flowchart LR
    Dev["Developer laptop"] -- "git push" --> GH["GitHub repository"]
    GH -- "GitHub Actions CI<br/>tests on PostgreSQL, E2E, docker build" --> CI["CI results"]
    GH -- "auto-deploy on commit" --> Build["Render build<br/>multi-stage Dockerfile<br/>Node build, Maven package, CDS training"]
    Build --> Svc

    subgraph Render["Render (free web service, Singapore)"]
        Edge["Render edge<br/>TLS termination, HTTPS"]
        Svc["Container: Spring Boot jar + React build<br/>0.1 CPU, 512 MB, single instance<br/>sleeps after 15 min idle"]
        Edge --> Svc
    end

    subgraph Neon["Neon (free PostgreSQL, AWS Singapore)"]
        PG[("PostgreSQL<br/>direct endpoint, TLS required<br/>scale-to-zero after 5 min")]
    end

    User["Users' browsers"] -- "HTTPS (REST, SSE, static files)" --> Edge
    Svc -- "JDBC over TLS, HikariCP max 5<br/>Flyway migrations at startup" --> PG
    Ops["Operator"] -. "env vars: DATABASE_URL, DATABASE_USERNAME,<br/>DATABASE_PASSWORD, QUEUELESS_SEED_*" .-> Svc
```

## Ticket state machine

Enforced by TicketStatus in Java and by CHECK constraints in PostgreSQL.

Source: [`08-ticket-state-machine.mmd`](08-ticket-state-machine.mmd)

```mermaid
stateDiagram-v2
    [*] --> BOOKED: appointment booked (token reserved)
    [*] --> WAITING: walk-in (customer or desk)
    BOOKED --> WAITING: check-in (owner or staff, from 60 min before)
    BOOKED --> CANCELLED: cancel (owner or staff)
    WAITING --> CALLED: call-next (staff, queue OPEN, counter free)
    WAITING --> CANCELLED: leave queue or staff cancel
    CALLED --> SERVING: start service (staff)
    CALLED --> NO_SHOW: mark no-show (staff)
    SERVING --> COMPLETED: complete (staff)
    COMPLETED --> [*]
    NO_SHOW --> [*]
    CANCELLED --> [*]
```
