#!/usr/bin/env bash
# Runs the whole project on this computer with demo data: http://localhost:8080
#
#   scripts/run-local.sh            build once (if needed) and start
#   scripts/run-local.sh --rebuild  rebuild after changing the code
#
# Needs: JDK 21, Node 22.22+, PostgreSQL server binaries (brew install postgresql@17).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Use JDK 21 on macOS even when another Java is the default.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  export JAVA_HOME
fi
JAVA="java"
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin/java"; fi

echo "1/3  Starting the local database"
scripts/local-postgres.sh start

JAR="backend/target/queueless.jar"
if [ "${1:-}" = "--rebuild" ] || [ ! -f "$JAR" ]; then
  echo "2/3  Building the frontend and the backend (takes a minute the first time)"
  (cd frontend && npm ci --no-audit --no-fund && npm run build)
  (cd backend && ./mvnw -q -B -DskipTests package)
else
  echo "2/3  Using the existing build (run with --rebuild after code changes)"
fi

echo "3/3  Starting QueueLess on http://localhost:8080   (press Ctrl+C to stop)"
echo "     Demo logins use the password Password123!  e.g. asha@example.com, staff@citycare.example"
exec "$JAVA" -jar "$JAR" --spring.profiles.active=dev
