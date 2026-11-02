# Next steps

These notes record the unfinished work so another session (local or cloud) can continue it
without the original conversation. Delete this file and the pointer in `AGENTS.md` once
everything below is done.

## Where things stand (2 November 2026)

- **`main`** is the last complete, working state. The full checks (`./mvnw verify`,
  `npm run build`, `npx playwright test`) passed at `f180d4a`. `2ccc7be` then simplified the
  deployment: a plain `Dockerfile`, a minimal `render.yaml`, and `scripts/run-local.sh`; it also
  removed CI, Compose and the Makefile. The smoke test passed afterwards (36/36).
- **`ui-redesign`** holds a half-finished UI redesign. `npm run build` fails on this branch
  until step 1 is done. Work on this branch and merge it into `main` only when every check
  passes again.

The remaining work should leave a project ready for a university presentation and viva:
- easy to deploy and to demo
- a guide on how to run and use it
- a project report with design diagrams
- a study guide for the viva
- a UI that looks plain and hand-built rather than template-generated

## 1. Finish the UI redesign (in progress)

**Style:**
- Fonts: IBM Plex Sans and Plex Mono (`@fontsource`, imported in `src/main.tsx`).
- Colors: a pine-green brand scale and a paper background (`@theme` in `src/index.css`), with
  stone greys.
- Layout: flat white panels with thin borders, and tables for lists.
- Statuses: a coloured dot plus a word.
- No icon library (`lucide-react` was removed), no gradients, no emoji, no em dashes.
- Wording: short, plain sentences.

**Building blocks:**
- `src/components/ui.tsx`:
  - `Button` (`variant` primary | secondary | danger | plain, `size` sm | md | lg, `loading`)
  - `Panel` (`title`, `actions`)
  - `PageTitle` (`title`, `description`, `actions`, `back`)
  - `Loading`, `Empty`
  - `Notice` (`tone` info | success | warning | error, `title`)
  - `ErrorNotice` (`error`, `onRetry`)
  - `Field`, `Input`, `Select`, `TextArea`, `Dialog`
  - `ConfirmDialog` (`open`, `title`, `confirmLabel`, `loading`, `onConfirm`, `onCancel`)
  - `Details` (`rows` of `[label, value]`)
  - `cx`, and the table class strings `table`, `th`, `td`
- `src/components/StatusBadges.tsx`: `QueueStatusBadge`, `TicketStatusBadge`,
  `AppointmentStatusBadge`, `PriorityBadge`, `LiveIndicator` (its text must stay "Live").

**Already rewritten** (use these as the reference):
- `Layout`, `Toast`, `StatusBadges`, `ui`
- `LandingPage`, `LoginPage`, `RegisterPage`, `OrganizationsPage`, `OrganizationPage`,
  `QueuePage`, `BookAppointmentPage`, `DashboardPage`, `TicketPage`, `AppointmentPage`,
  `NotificationsPage`

**Still on the old UI.** These files import components that no longer exist (`Card`, `Badge`,
`Spinner`, `EmptyState`, `ErrorState`, `Alert`, `Stat`, `PageHeader`) or `lucide-react` icons:
- `pages/staff/StaffHomePage.tsx`, `pages/staff/StaffQueuePage.tsx`
- `pages/admin/AdminHomePage.tsx`, `pages/admin/AdminOrganizationPage.tsx`,
  `pages/admin/QueueFormDialog.tsx`
- `pages/platform/PlatformPage.tsx`
- `pages/NotFoundPage.tsx` (also exports `RouteErrorPage`), `pages/AccessDeniedPage.tsx`
- `auth/guards.tsx` (switch it to `ErrorNotice` and `Loading`)
- `components/QueueLivePanel.tsx` and `components/TicketCard.tsx`: delete them once nothing
  imports them. Move `estimateBasisText` to wherever it is still needed.

Leave the data layer as it is: `api/queries.ts`, `api/client.ts`, `realtime/hooks.ts`,
`lib/format.ts`, `router.tsx`.

**Playwright selectors.** The specs in `frontend/e2e` find elements by the texts and attributes
below. Keep them, or update the specs:
- Texts:
  - "Get a token", "Confirm booking", "Booking confirmed", "Cancel appointment"
  - "Call next", "Start service", "Complete", "No-show"
  - "The counter is free.", "Service completed", "It's your turn!"
  - "New queue", "Create queue", "Add"
- The "Members" tab and the "Role of …" selects.
- The "Live" indicator.
- `data-testid="token"`.
- The day chips (`role=listbox` / `option`) and the slot buttons (`aria-pressed`).

**Renames already made.** The specs still need to follow these:
- "Choose a slot" is now "Book an appointment".
- "Back to my queue" is now "Back to my visits".
- The dashboard heading is "My visits".
- The ticket position is in `data-testid="position"` ("Number N in line").
- `TicketPage` writes "It’s your turn!" with a typographic apostrophe; match what the spec
  expects.

## 2. Re-verify everything

Run these in order, then open every page in a browser at desktop and phone widths and fix
anything that looks off:
1. `npm run build`
2. `./mvnw verify`
3. Rebuild and restart the app: `scripts/run-local.sh --rebuild`
4. `npx playwright test`

Environment:
- JDK 21 and Node 24 (as in the `Dockerfile`).
- PostgreSQL server binaries (`brew install postgresql@17` or `apt install postgresql`) for
  `scripts/local-postgres.sh`. It runs PostgreSQL on port 5433 with the development and test
  databases.
- Integration tests can point at another PostgreSQL through `QUEUELESS_TEST_DATABASE_URL`,
  `QUEUELESS_TEST_DATABASE_USERNAME` and `QUEUELESS_TEST_DATABASE_PASSWORD`.
- Playwright needs `npx playwright install chromium`.

## 3. Design diagrams for the report

`docs/diagrams` holds eight Mermaid sources (`01` to `08`).
- Add:
  - a use case diagram (visitor, staff, organization admin, platform admin)
  - level 0 and level 1 data flow diagrams
  - a class diagram of the domain model
  - an activity diagram of one visit: join or book, check in, called, then served (or no-show
    or cancelled)
- Redraw `07-deployment-architecture.mmd` without CI and CDS: GitHub, then Render building the
  `Dockerfile` into one container that serves both the React build and the API, then Neon
  PostgreSQL.
- Render every diagram to PNG in `docs/diagrams/png` (for example with
  `npx @mermaid-js/mermaid-cli`) and list them in `docs/diagrams/README.md`.

## 4. Screenshots

Run the app with demo data (`scripts/run-local.sh`). Capture these screens at 1280 px wide into
`docs/screenshots`:
- home, find a service, an organization, a queue, booking
- a ticket while waiting and when called
- my visits, notifications
- the staff console
- the admin organization page (queues and members)
- the platform page
- log in

The login page lists the demo accounts. The local development password is `Password123!`.

## 5. Project report

Write `docs/report/project-report.md` (plus a Word copy if a Word tool is available) in the
usual university project format:
- abstract
- introduction (problem, objectives, scope)
- existing systems
- requirements (functional, non-functional, use cases)
- system design: architecture, modules, database and ER diagram, sequence diagrams, ticket state
  machine, data flow and class diagrams
- implementation: stack, token numbering under the queue lock, ordering and positions, wait
  estimates, capacity-safe idempotent booking, live updates after commit, security
- testing: unit, integration on PostgreSQL, concurrency, end-to-end, smoke. Quote only numbers
  from real runs.
- deployment
- results, with screenshots
- limitations and future work
- conclusion
- references
- appendix: API summary

Take the technical content from the existing `docs/*.md` and the ADRs. Do not invent features
or results.

Earlier, headings-only drafts of the report and the study guide were created as Claude Docs in
the owner's account. A session with the Docs connector can fill those as well; otherwise the
repository files are the deliverable.

## 6. Study guide and user guide

**`docs/study-guide.md`** replaces `docs/viva-preparation.md`. It should let the author defend
every part of the project:
- the problem and the design, in plain words
- one request traced through every layer, for example "Get a token": controller, service,
  `QueueLockService`, repositories, the after-commit event, SSE, then the React Query refetch
- why each database constraint and lock exists, and what goes wrong without it
- sessions, CSRF and roles
- the test strategy
- deployment
- trade-offs, and what would change at a larger scale
- 40 or more likely viva questions, with short answers

**`docs/user-guide.md`** covers:
- installing the prerequisites and running the app (`scripts/run-local.sh`)
- the demo accounts and what each role can do
- a step-by-step demo for the presentation:
  1. A visitor takes a token and books a slot.
  2. Staff call and serve tickets.
  3. An admin creates a queue and adds a member.
  4. Two browser windows side by side show the live updates.

## 7. Tidy the repository docs

- Remove references to things that no longer exist: the CI workflow, `docker-compose.yml`,
  `scripts/docker-init/`, the `Makefile`, CDS. They appear in:
  - `README.md`
  - `docs/deployment.md`, `docs/testing.md`, `docs/roadmap.md` (Phase 7),
    `docs/architecture.md`
  - `docs/adr/0002-*.md`
  - `docs/diagrams/README.md` and `07-deployment-architecture.mmd`
- Reduce `docs/deployment.md` to the Render and Neon steps below.
- Make `README.md` a short front page: what the project is, screenshots, how to run it, the demo
  accounts, links to the guides.

## 8. Deploy on Render and Neon (free plans)

The account steps belong to the owner:
1. Create a Neon project.
2. Open Connect, choose Java with connection pooling off, and copy the JDBC string.
3. In Render, choose New, then Blueprint, and pick this repository.
4. Paste the JDBC string as `DATABASE_URL`, and choose a demo password for
   `QUEUELESS_SEED_PASSWORD`.

Then check the deployed app:
1. Run `QUEUELESS_DEMO_PASSWORD=... scripts/smoke-test.sh https://<service>.onrender.com`.
2. Restart the service in Render.
3. Run the smoke test again with `--after-restart`, to show the data persisted.

A free Render service sleeps after 15 minutes without traffic, and the first request after that
takes about a minute. Open the app a few minutes before the presentation.

## 9. Final check

Get `./mvnw verify`, `npm run build` and `npx playwright test` green. Then merge `ui-redesign`
into `main`, push, and delete this file and its pointer in `AGENTS.md`.
