# Convenience targets (macOS/Linux). Each target is a thin wrapper around a documented command;
# see README.md for the raw commands. Requires JDK 21+ (JAVA_HOME), Node 22.22+ and PostgreSQL.

.PHONY: db-start db-stop db-reset backend frontend test unit-test frontend-check package run-prod e2e smoke

db-start:            ## start the project-local PostgreSQL on port 5433 (no Docker needed)
	scripts/local-postgres.sh start

db-stop:
	scripts/local-postgres.sh stop

db-reset:            ## drop and recreate the development database (Flyway rebuilds it on start)
	scripts/local-postgres.sh reset-dev

backend:             ## Spring Boot API on :8080 with demo data (dev profile)
	cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

frontend:            ## Vite dev server on :5173, proxying /api to :8080
	cd frontend && npm install && npm run dev

unit-test:           ## backend unit tests (no database)
	cd backend && ./mvnw -B test

test:                ## backend unit + integration + concurrency tests (needs PostgreSQL)
	cd backend && ./mvnw -B verify

frontend-check:      ## TypeScript check + production build
	cd frontend && npm ci && npm run build

package: frontend-check  ## single deployable jar containing the React build
	cd backend && ./mvnw -B -DskipTests package

run-prod:            ## run the packaged jar with the dev profile (demo data) on :8080
	java -jar backend/target/queueless.jar --spring.profiles.active=dev

e2e:                 ## Playwright end-to-end tests against the packaged jar
	cd frontend && npx playwright install chromium && npx playwright test

smoke:               ## smoke test a running deployment: make smoke URL=https://... PASSWORD=...
	QUEUELESS_DEMO_PASSWORD="$(PASSWORD)" scripts/smoke-test.sh "$(URL)"
