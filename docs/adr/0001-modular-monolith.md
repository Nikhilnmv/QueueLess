# ADR 0001: Modular monolith instead of microservices

- **Status:** accepted
- **Date:** 2026-10-29

## Context

QueueLess is built by one student in 1–2 weeks and must run on free hosting (one 0.1-CPU,
512 MB instance). Its core operations cross business areas in a single step: booking an
appointment also reserves a token, creates a notification and updates the live queue, and all of
that must succeed or fail together.

## Decision

Build one Spring Boot application with feature modules (`auth`, `organization`, `membership`,
`queue`, `appointment`, `ticket`, `notification`, `realtime`, `common`), each split into
`api`/`service`/`domain`, with acyclic dependencies (two interfaces invert the dependencies that
would otherwise form cycles). One PostgreSQL database; one deployable that also serves the React
build.

## Consequences

- Cross-module operations are ordinary local transactions: atomic booking + token + notification
  without sagas, outboxes or distributed locks.
- One process to run, test (one Spring context in tests) and deploy; fits the free instance.
- No network calls between components: fewer failure modes, no service discovery.
- Scaling is vertical first; the boundaries allow extraction later (see
  [scalability-and-tradeoffs.md](../scalability-and-tradeoffs.md#4-when-microservices-would-be-justified)).
- Discipline is needed to keep boundaries (DTOs at the API, no repository access across modules
  for writes); read-only projections across tables are accepted and documented.

## Alternatives considered

- **Microservices** (separate booking, queue and notification services): would need distributed
  transactions or eventual consistency for booking + token, a message broker, several free
  instances (not available), and much more operational work — unjustified at this scale.
- **Layer-first packages** (`controllers`, `services`, `repositories`): simpler at first but
  hides business boundaries and makes later extraction harder.
