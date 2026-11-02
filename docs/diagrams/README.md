# Diagrams

Editable Mermaid sources live next to this file (`*.mmd`), with a PNG of each in [`png/`](png/)
for slides and documents. GitHub renders the blocks below. After editing a source, run
`node scripts/render-diagrams.mjs` to refresh this page and the PNGs (the one-time setup is at the
top of that script).

## High-level system architecture

Browser, Spring Boot modular monolith and PostgreSQL. One origin serves the React build, the REST API and the SSE streams.

Source: [`01-system-architecture.mmd`](01-system-architecture.mmd) · PNG: [`png/01-system-architecture.png`](png/01-system-architecture.png)

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

Source: [`02-entity-relationship.mmd`](02-entity-relationship.mmd) · PNG: [`png/02-entity-relationship.png`](png/02-entity-relationship.png)

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

Source: [`03-appointment-booking-sequence.mmd`](03-appointment-booking-sequence.mmd) · PNG: [`png/03-appointment-booking-sequence.png`](png/03-appointment-booking-sequence.png)

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

Source: [`04-token-generation-sequence.mmd`](04-token-generation-sequence.mmd) · PNG: [`png/04-token-generation-sequence.png`](png/04-token-generation-sequence.png)

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

Source: [`05-call-next-sequence.mmd`](05-call-next-sequence.mmd) · PNG: [`png/05-call-next-sequence.png`](png/05-call-next-sequence.png)

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

Source: [`06-live-update-sequence.mmd`](06-live-update-sequence.mmd) · PNG: [`png/06-live-update-sequence.png`](png/06-live-update-sequence.png)

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

GitHub, Render and Neon. Render builds the `Dockerfile` into one container that serves the React build, the REST API and the live updates; Neon hosts PostgreSQL. Both are free plans.

Source: [`07-deployment-architecture.mmd`](07-deployment-architecture.mmd) · PNG: [`png/07-deployment-architecture.png`](png/07-deployment-architecture.png)

```mermaid
flowchart LR
    Dev["Developer laptop"] -- "git push" --> GH["GitHub repository"]
    GH -- "deploys on every push to main" --> Build["Render build<br/>Dockerfile: Node builds the React app,<br/>Maven packages it into the jar"]
    Build --> Svc

    subgraph Render["Render (free web service, Singapore)"]
        Edge["Render edge<br/>HTTPS"]
        Svc["One container: Spring Boot jar<br/>serves the React build, REST API and SSE<br/>0.1 CPU, 512 MB, sleeps after 15 min idle"]
        Edge --> Svc
    end

    subgraph Neon["Neon (free PostgreSQL, same region)"]
        PG[("PostgreSQL<br/>TLS required<br/>scales to zero when idle")]
    end

    User["Users' browsers"] -- "HTTPS: pages, REST, SSE" --> Edge
    Svc -- "JDBC over TLS (HikariCP)<br/>Flyway migrations at startup" --> PG
    Owner["Project owner"] -. "Render environment variables:<br/>DATABASE_URL, QUEUELESS_SEED_PASSWORD" .-> Svc
```

## Ticket state machine

Enforced by TicketStatus in Java and by CHECK constraints in PostgreSQL.

Source: [`08-ticket-state-machine.mmd`](08-ticket-state-machine.mmd) · PNG: [`png/08-ticket-state-machine.png`](png/08-ticket-state-machine.png)

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

## Use case diagram

What each kind of user can do. An organization admin can also do everything staff can. A walk-in visitor without an account is registered by staff.

Source: [`09-use-case.mmd`](09-use-case.mmd) · PNG: [`png/09-use-case.png`](png/09-use-case.png)

```mermaid
flowchart LR
    Visitor["Visitor<br/>(registered user)"]

    subgraph System["QueueLess"]
        subgraph Visiting["Visiting"]
            V1(["Register and log in"])
            V2(["Find a service and see live queue numbers"])
            V3(["Take a token for today"])
            V4(["Book an appointment slot"])
            V5(["Check in on arrival"])
            V6(["Follow position and estimated wait live"])
            V7(["Cancel a ticket or an appointment"])
            V8(["Read notifications"])
        end
        subgraph Counter["Running a counter"]
            S1(["Open, pause or close a queue"])
            S2(["Call next, start, complete or mark no-show"])
            S3(["Register a walk-in without an account"])
            S4(["Change a ticket's priority"])
            S5(["Check in or cancel for a visitor"])
        end
        subgraph Administration["Administration"]
            A1(["Create and edit queues"])
            A2(["Add members and change roles"])
            A3(["Edit the organization profile"])
            P1(["Create an organization and its first admin"])
        end
    end

    Staff["Staff"]
    OrgAdmin["Organization admin<br/>(can also do everything staff do)"]
    PlatformAdmin["Platform admin"]

    Visitor --- V1 & V2 & V3 & V4 & V5 & V6 & V7 & V8
    S1 & S2 & S3 & S4 & S5 --- Staff
    A1 & A2 & A3 --- OrgAdmin
    P1 --- PlatformAdmin
```

## Data flow diagram, level 0 (context)

QueueLess as a single process and the four kinds of users that exchange data with it.

Source: [`10-dfd-level-0.mmd`](10-dfd-level-0.mmd) · PNG: [`png/10-dfd-level-0.png`](png/10-dfd-level-0.png)

```mermaid
flowchart TB
    Visitor["Visitor"]
    System(("0<br/>QueueLess"))
    Staff["Staff"]
    Admin["Organization admin"]
    Platform["Platform admin"]

    Visitor -- "account details, join and booking<br/>requests, check-ins, cancellations" --> System
    System -- "tokens, positions, waits, confirmations,<br/>notifications, live updates" --> Visitor
    System -- "live board, counts,<br/>averages" --> Staff
    Staff -- "queue status, call next, start,<br/>complete, no-show, priority,<br/>walk-in details" --> System
    System -- "queues and<br/>members" --> Admin
    Admin -- "queue settings,<br/>members and roles,<br/>profile" --> System
    System -- "created<br/>organization" --> Platform
    Platform -- "new organization,<br/>first admin's email" --> System
```

## Data flow diagram, level 1

The main processes inside QueueLess, the data stores they read and write (D1 to D6 are groups of database tables) and the flows between them. Change events reach process 7.0 only after the database transaction commits.

Source: [`11-dfd-level-1.mmd`](11-dfd-level-1.mmd) · PNG: [`png/11-dfd-level-1.png`](png/11-dfd-level-1.png)

```mermaid
flowchart TB
    Visitor["Visitor"]
    Staff["Staff"]
    Admin["Organization admin<br/>or platform admin"]

    P1(("1.0<br/>Authenticate"))
    P2(("2.0<br/>Browse services"))
    P3(("3.0<br/>Book an<br/>appointment"))
    P4(("4.0<br/>Issue and check in<br/>tokens"))
    P5(("5.0<br/>Operate the<br/>counter"))
    P6(("6.0<br/>Compute positions<br/>and waits"))
    P7(("7.0<br/>Notify and push<br/>live updates"))
    P8(("8.0<br/>Administer<br/>organizations"))

    D1[("D1 Users and sessions")]
    D2[("D2 Organizations and memberships")]
    D3[("D3 Queues")]
    D4[("D4 Appointments")]
    D5[("D5 Tickets")]
    D6[("D6 Notifications")]

    Visitor -- "email, password" --> P1
    P1 <-- "user, session" --> D1
    Visitor -- "search" --> P2
    D2 -- "organizations" --> P2
    D3 -- "queues, hours" --> P2
    P2 -- "service list, live numbers" --> Visitor
    Visitor -- "slot, idempotency key" --> P3
    D3 -- "schedule, capacity" --> P3
    P3 <-- "appointment" --> D4
    P3 -- "reserved token" --> D5
    Visitor -- "join, check-in, cancel" --> P4
    Staff -- "walk-in name, check-in" --> P4
    P4 <-- "ticket with the next token" --> D5
    Staff -- "call next, start, complete,<br/>no-show, priority" --> P5
    P5 <-- "ticket status" --> D5
    D3 -- "queue status" --> P5
    D5 -- "waiting line,<br/>recent service times" --> P6
    D3 -- "typical service time" --> P6
    P6 -- "position, estimated wait" --> Visitor
    P6 -- "live board" --> Staff
    P3 -- "change event" --> P7
    P4 -- "change event" --> P7
    P5 -- "change event" --> P7
    P7 <-- "notifications" --> D6
    P7 -- "SSE events, notifications" --> Visitor
    P7 -- "SSE events" --> Staff
    Admin -- "queue settings, roles,<br/>new organization" --> P8
    P8 <-- "organizations, memberships" --> D2
    P8 <-- "queue settings" --> D3
```

## Class diagram: domain model

The JPA entities and their enums. In the code the associations are id columns (for example `QueueTicket.queueId`), not object references; the multiplicities follow the foreign keys and unique constraints.

Source: [`12-class-domain-model.mmd`](12-class-domain-model.mmd) · PNG: [`png/12-class-domain-model.png`](png/12-class-domain-model.png)

```mermaid
classDiagram
    direction LR
    class User {
        UUID id
        String email
        String fullName
        String passwordHash
        boolean enabled
        boolean platformAdmin
    }
    class Organization {
        UUID id
        String name
        String slug
        String description
        String timezone
        boolean active
    }
    class OrganizationMembership {
        UUID id
        MembershipRole role
    }
    class ServiceQueue {
        UUID id
        String name
        String tokenPrefix
        QueueStatus status
        boolean bookingEnabled
        LocalTime openingTime
        LocalTime closingTime
        int slotDurationMinutes
        int capacityPerSlot
        int defaultServiceMinutes
        long stateVersion
    }
    class Appointment {
        UUID id
        Instant scheduledAt
        LocalDate serviceDate
        String bookingReference
        AppointmentStatus status
        UUID idempotencyKey
        Instant cancelledAt
    }
    class QueueTicket {
        UUID id
        String guestName
        TicketSource source
        LocalDate serviceDate
        int tokenNumber
        TicketStatus status
        TicketPriority priority
        Instant checkedInAt
        Instant calledAt
        Instant serviceStartedAt
        Instant completedAt
        Instant approachNotifiedAt
    }
    class Notification {
        UUID id
        NotificationType type
        String title
        String message
        Instant readAt
    }
    class MembershipRole {
        <<enumeration>>
        CUSTOMER
        STAFF
        ADMIN
    }
    class QueueStatus {
        <<enumeration>>
        OPEN
        PAUSED
        CLOSED
    }
    class AppointmentStatus {
        <<enumeration>>
        BOOKED
        CHECKED_IN
        COMPLETED
        NO_SHOW
        CANCELLED
    }
    class TicketStatus {
        <<enumeration>>
        BOOKED
        WAITING
        CALLED
        SERVING
        COMPLETED
        NO_SHOW
        CANCELLED
    }
    class TicketPriority {
        <<enumeration>>
        HIGH
        NORMAL
        LOW
    }
    class TicketSource {
        <<enumeration>>
        APPOINTMENT
        WALK_IN
        GUEST_WALK_IN
    }
    class NotificationType {
        <<enumeration>>
        APPOINTMENT_CONFIRMED
        APPOINTMENT_CANCELLED
        TICKET_CANCELLED
        TURN_APPROACHING
        TURN_CALLED
        MARKED_NO_SHOW
    }

    User "1" -- "0..*" OrganizationMembership : has
    Organization "1" -- "0..*" OrganizationMembership : grants
    Organization "1" *-- "0..*" ServiceQueue : runs
    ServiceQueue "1" *-- "0..*" Appointment : books
    ServiceQueue "1" *-- "0..*" QueueTicket : issues
    User "1" -- "0..*" Appointment : makes
    User "0..1" -- "0..*" QueueTicket : holds
    Appointment "1" -- "0..1" QueueTicket : reserves
    User "1" -- "0..*" Notification : receives
    QueueTicket "0..1" -- "0..*" Notification : about
    OrganizationMembership ..> MembershipRole
    ServiceQueue ..> QueueStatus
    Appointment ..> AppointmentStatus
    QueueTicket ..> TicketStatus
    QueueTicket ..> TicketPriority
    QueueTicket ..> TicketSource
    Notification ..> NotificationType
```

## Class diagram: service layer

The services behind the API and how they depend on each other. `LockedQueue` has a package-private constructor and is created only by `QueueLockService.lock`, so a method that takes one can rely on the queue row being locked. Events go to `RealtimeEventRelay`, which sends SSE messages only after commit.

Source: [`13-class-services.mmd`](13-class-services.mmd) · PNG: [`png/13-class-services.png`](png/13-class-services.png)

```mermaid
classDiagram
    direction TB
    class QueueLockService {
        +lock(queueId) LockedQueue
    }
    class LockedQueue {
        -ServiceQueue queue
        ~LockedQueue(queue)
    }
    class TokenAllocator {
        +nextToken(LockedQueue, serviceDate) int
    }
    class TicketService {
        +joinQueue(queueId, userId) TicketResponse
        +registerGuest(queueId, staffId, guestName, priority) BoardTicket
        +reserveForAppointment(LockedQueue, appointmentId, userId) QueueTicket
        +checkIn(LockedQueue, ticket, actorId)
        +cancel(ticketId, actorId) TicketResponse
    }
    class QueueOperationsService {
        +callNext(queueId, staffId) BoardTicket
        +startService(ticketId, staffId) BoardTicket
        +complete(ticketId, staffId) BoardTicket
        +markNoShow(ticketId, staffId) BoardTicket
        +changePriority(ticketId, staffId, priority) BoardTicket
    }
    class AppointmentService {
        +slots(queueId, date) SlotsResponse
        +book(queueId, userId, start, idempotencyKey) BookingResult
        +checkIn(appointmentId, actorId) TicketResponse
        +cancel(appointmentId, actorId) AppointmentResponse
    }
    class QueueReadService {
        +live(queueId) QueueLiveResponse
        +board(queueId, staffId) QueueBoardResponse
        +ticket(ticketId, actorId) TicketResponse
    }
    class WaitTimeEstimator {
        <<utility>>
        +serviceTimeStats(recentDurations, defaultMinutes) ServiceTimeStats
        +estimateWaitMinutes(peopleAhead, remainingAtCounter, stats) int
        +positions(waitingCount, remainingAtCounter, stats) List~Position~
    }
    class AccessControlService {
        +require(userId, organizationId, permission) MembershipRole
        +has(userId, organizationId, permission) boolean
    }
    class QueueChangeRecorder {
        +record(LockedQueue, serviceDate, reason) long
    }
    class NotificationService {
        +notify(userId, ticketId, type, title, message) Notification
        +list(userId, unreadOnly, limit) NotificationListResponse
        +markRead(userId, notificationId) NotificationResponse
    }
    class RealtimeEventRelay {
        <<after commit listener>>
    }
    class RealtimeGateway {
        <<interface>>
        +sendToQueue(queueId, eventName, payload)
        +sendToUser(userId, eventName, payload)
    }
    class SseEmitterRegistry {
        +subscribeToQueue(queueId, userId, readyPayload) SseEmitter
        +subscribeToUser(userId, readyPayload) SseEmitter
    }

    QueueLockService ..> LockedQueue : creates
    TokenAllocator ..> LockedQueue : needs
    TicketService --> QueueLockService
    TicketService --> TokenAllocator
    QueueOperationsService --> QueueLockService
    AppointmentService --> QueueLockService
    AppointmentService --> TicketService
    TicketService --> AccessControlService
    QueueOperationsService --> AccessControlService
    AppointmentService --> AccessControlService
    QueueReadService --> AccessControlService
    QueueReadService ..> WaitTimeEstimator : uses
    TicketService --> QueueChangeRecorder
    QueueOperationsService --> QueueChangeRecorder
    AppointmentService --> QueueChangeRecorder
    QueueChangeRecorder --> NotificationService
    QueueChangeRecorder ..> RealtimeEventRelay : QueueChangedEvent
    NotificationService ..> RealtimeEventRelay : UserNotificationEvent
    RealtimeEventRelay --> RealtimeGateway
    RealtimeGateway ..> SseEmitterRegistry : SSE implementation uses
```

## Activity diagram: one visit

A visitor's path from walking in or booking to being served, with the cancel, no-show and expiry exits. The states match the ticket state machine above.

Source: [`14-activity-visit.mmd`](14-activity-visit.mmd) · PNG: [`png/14-activity-visit.png`](png/14-activity-visit.png)

```mermaid
flowchart TD
    Start((Start)) --> Choose{"Walk in or book?"}
    Choose -- "walk in" --> Join["Take a token for today<br/>(next number under the queue lock)"]
    Choose -- "book" --> Slot["Pick a day and a free slot"]
    Slot --> Book["Confirm the booking<br/>(capacity check, idempotency key)"]
    Book --> Reserved["Token reserved: BOOKED<br/>confirmation notification"]
    Reserved --> Arrive{"Comes on the day?"}
    Arrive -- "cancels" --> Cancelled["CANCELLED<br/>(a booked slot becomes free)"]
    Arrive -- "does not come" --> Expired["Ticket expires after the day"]
    Arrive -- "yes, from 60 min before" --> CheckIn["Check in<br/>(visitor or staff)"]
    CheckIn --> Waiting["WAITING in line<br/>position and estimate update live"]
    Join --> Waiting
    Waiting -- "leaves the queue" --> Cancelled
    Waiting --> Called["Staff call next: CALLED<br/>notification: it's your turn"]
    Called --> Present{"At the counter?"}
    Present -- "no" --> NoShow["NO_SHOW"]
    Present -- "yes" --> Serving["Staff start service: SERVING"]
    Serving --> Completed["Staff complete: COMPLETED"]
    Completed --> Stop(((End)))
    NoShow --> Stop
    Cancelled --> Stop
    Expired --> Stop
```
