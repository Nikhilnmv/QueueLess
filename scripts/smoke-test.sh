#!/usr/bin/env bash
# End-to-end smoke test against a running QueueLess deployment (local jar or hosted URL).
#
#   scripts/smoke-test.sh https://your-app.onrender.com
#   scripts/smoke-test.sh https://your-app.onrender.com --after-restart
#
# Requires the demo seed (QUEUELESS_SEED_DEMO_DATA=true) and the demo password in
# QUEUELESS_DEMO_PASSWORD. The first run creates a fresh customer, books and cancels an
# appointment, creates a dedicated "Smoke test" queue (closed again at the end), runs the staff
# lifecycle while listening to SSE, and saves the customer's credentials to .smoke-state.
# After restarting the service, run again with --after-restart to prove the data persisted.
# Needs: bash, curl, python3.
set -uo pipefail

BASE="${1:?usage: smoke-test.sh BASE_URL [--after-restart]}"
BASE="${BASE%/}"
MODE="${2:-}"
DEMO_PASSWORD="${QUEUELESS_DEMO_PASSWORD:?set QUEUELESS_DEMO_PASSWORD to the demo accounts password}"
WORK="$(mktemp -d)"
STATE_FILE="$(cd "$(dirname "$0")/.." && pwd)/.smoke-state"
PASS=0
FAIL=0
trap 'rm -rf "$WORK"' EXIT

green() { printf '\033[32m%s\033[0m\n' "$*"; }
red() { printf '\033[31m%s\033[0m\n' "$*"; }
check() { # check "description" condition-exit-code
  if [ "$2" -eq 0 ]; then PASS=$((PASS + 1)); green "  PASS  $1"; else FAIL=$((FAIL + 1)); red "  FAIL  $1"; fi
}
json() { python3 -c "import sys, json; d = json.load(sys.stdin); print($1)"; }

# request JAR METHOD PATH [JSON_BODY] [EXTRA_HEADER] -> prints the body; the HTTP status is
# written to a file because callers usually run request inside $(...), a subshell.
request() {
  local jar="$1" method="$2" path="$3" body="${4:-}" extra="${5:-}"
  local args=(-s -o "$WORK/body" -w '%{http_code}' -b "$jar" -c "$jar" -X "$method" -H 'Accept: application/json')
  if [ "$method" != "GET" ]; then
    if ! grep -q XSRF-TOKEN "$jar" 2>/dev/null; then curl -s -o /dev/null -b "$jar" -c "$jar" "$BASE/api/auth/me"; fi
    args+=(-H "X-XSRF-TOKEN: $(awk '$6 == "XSRF-TOKEN" {print $7}' "$jar" | tail -1)")
  fi
  [ -n "$body" ] && args+=(-H 'Content-Type: application/json' --data "$body")
  [ -n "$extra" ] && args+=(-H "$extra")
  curl "${args[@]}" "$BASE$path" > "$WORK/status"
  cat "$WORK/body"
}
status() { cat "$WORK/status"; }

login() { # login JAR EMAIL PASSWORD
  rm -f "$1"
  request "$1" POST /api/auth/login "{\"email\":\"$2\",\"password\":\"$3\"}" > /dev/null
  [ "$(status)" = "200" ]
}

if [ "$MODE" = "--after-restart" ]; then
  echo "== Persistence check after restart against $BASE"
  [ -f "$STATE_FILE" ] || { red "No $STATE_FILE: run the normal smoke test first"; exit 1; }
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  login "$WORK/c" "$SMOKE_EMAIL" "$SMOKE_PASSWORD"; check "customer can still log in" $?
  notes=$(request "$WORK/c" GET /api/me/notifications)
  echo "$notes" | json "any(n['type'] == 'TURN_CALLED' for n in d['items'])" | grep -q True; check "TURN_CALLED notification persisted" $?
  ticket=$(request "$WORK/c" GET "/api/tickets/$SMOKE_TICKET")
  echo "$ticket" | json "d['status']" | grep -q COMPLETED; check "completed ticket persisted" $?
  appt=$(request "$WORK/c" GET "/api/appointments/$SMOKE_APPOINTMENT")
  echo "$appt" | json "d['status']" | grep -q CANCELLED; check "cancelled appointment persisted" $?
  echo; echo "Passed: $PASS  Failed: $FAIL"; [ "$FAIL" -eq 0 ]; exit
fi

echo "== Smoke test against $BASE"

health=$(curl -s "$BASE/actuator/health")
echo "$health" | json "d['status'] == 'UP' and d['components']['db']['status'] == 'UP'" | grep -q True
check "health UP with database UP" $?
curl -s "$BASE/actuator/health/liveness" | grep -q '"UP"'; check "liveness probe UP" $?

curl -s "$BASE/" | grep -q '<div id="root">'; check "SPA index served at /" $?
curl -s "$BASE/organizations/city-care-clinic" | grep -q '<div id="root">'; check "deep link served by SPA fallback" $?
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/does-not-exist"); [ "$code" = "401" ]; check "unknown API path needs login, revealing nothing (got $code)" $?
code=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/me/appointments"); [ "$code" = "401" ]; check "protected API requires login (got $code)" $?

orgs=$(request "$WORK/anon" GET /api/organizations)
echo "$orgs" | grep -q city-care-clinic && echo "$orgs" | grep -q northbridge-college
check "two demo organizations discoverable" $?
QUEUE=$(request "$WORK/anon" GET /api/organizations/city-care-clinic/queues | json "[q['id'] for q in d if q['name'] == 'General OPD'][0]")

# --- customer registration and login -------------------------------------------------------
STAMP=$(date +%s)
EMAIL="smoke-$STAMP@example.com"
PASSWORD="smoke-pass-$STAMP"
request "$WORK/c" POST /api/auth/register "{\"email\":\"$EMAIL\",\"fullName\":\"Smoke Tester\",\"password\":\"$PASSWORD\"}" > /dev/null
[ "$(status)" = "201" ]; check "register new customer (201)" $?
request "$WORK/c" POST /api/auth/logout > /dev/null; [ "$(status)" = "204" ]; check "logout (204)" $?
login "$WORK/c" "$EMAIL" "$PASSWORD"; check "login again" $?
request "$WORK/c" GET /api/does-not-exist > /dev/null; [ "$(status)" = "404" ]; check "unknown API path is a 404 when logged in" $?
request "$WORK/x" POST /api/auth/login "{\"email\":\"$EMAIL\",\"password\":\"wrong\"}" > /dev/null
[ "$(status)" = "401" ]; check "wrong password rejected (401)" $?

# --- booking, idempotency, cancellation and capacity release --------------------------------
TOMORROW=$(python3 -c "import datetime, zoneinfo; print((datetime.datetime.now(zoneinfo.ZoneInfo('Asia/Kolkata')).date() + datetime.timedelta(days=1)).isoformat())")
slots=$(request "$WORK/c" GET "/api/queues/$QUEUE/slots?date=$TOMORROW")
SLOT=$(echo "$slots" | json "[s['startsAt'] for s in d['slots'] if s['bookable']][0]")
BEFORE=$(echo "$slots" | json "[s['available'] for s in d['slots'] if s['startsAt'] == '$SLOT'][0]")
KEY=$(python3 -c "import uuid; print(uuid.uuid4())")
booked=$(request "$WORK/c" POST "/api/queues/$QUEUE/appointments" "{\"scheduledAt\":\"$SLOT\"}" "Idempotency-Key: $KEY")
[ "$(status)" = "201" ]; check "book appointment (201)" $?
APPOINTMENT=$(echo "$booked" | json "d['id']")
echo "$booked" | json "d['bookingReference']" | grep -q -E '^QL-'; check "booking reference issued" $?
replay=$(request "$WORK/c" POST "/api/queues/$QUEUE/appointments" "{\"scheduledAt\":\"$SLOT\"}" "Idempotency-Key: $KEY")
[ "$(status)" = "200" ] && [ "$(echo "$replay" | json "d['id']")" = "$APPOINTMENT" ]; check "duplicate submission returns the same booking" $?
AFTER=$(request "$WORK/c" GET "/api/queues/$QUEUE/slots?date=$TOMORROW" | json "[s['available'] for s in d['slots'] if s['startsAt'] == '$SLOT'][0]")
[ "$AFTER" -eq $((BEFORE - 1)) ]; check "booking consumed one place ($BEFORE -> $AFTER)" $?
request "$WORK/c" POST "/api/appointments/$APPOINTMENT/cancel" | json "d['status']" | grep -q CANCELLED; check "cancel appointment" $?
RELEASED=$(request "$WORK/c" GET "/api/queues/$QUEUE/slots?date=$TOMORROW" | json "[s['available'] for s in d['slots'] if s['startsAt'] == '$SLOT'][0]")
[ "$RELEASED" -eq "$BEFORE" ]; check "cancellation released the place" $?
request "$WORK/c" POST "/api/appointments/$APPOINTMENT/cancel" > /dev/null; [ "$(status)" = "200" ]; check "repeated cancellation is idempotent" $?

# --- dedicated smoke queue: tokens, staff lifecycle, SSE, notifications --------------------
login "$WORK/admin" admin@citycare.example "$DEMO_PASSWORD"; check "demo admin login" $?
ORG=$(request "$WORK/admin" GET /api/auth/me | json "[m['organizationId'] for m in d['memberships'] if m['organizationSlug'] == 'city-care-clinic'][0]")
SMOKE_QUEUE=$(request "$WORK/admin" POST "/api/admin/organizations/$ORG/queues" \
  "{\"name\":\"Smoke test $STAMP\",\"description\":\"Created by scripts/smoke-test.sh\",\"tokenPrefix\":\"S\",\"openingTime\":\"00:00\",\"closingTime\":\"23:45\",\"slotDurationMinutes\":15,\"capacityPerSlot\":1,\"defaultServiceMinutes\":5,\"bookingEnabled\":false}" | json "d['id']")
[ "$(status)" = "201" ]; check "admin creates a queue" $?
login "$WORK/staff" staff@citycare.example "$DEMO_PASSWORD"; check "demo staff login" $?
request "$WORK/staff" POST "/api/staff/queues/$SMOKE_QUEUE/status" '{"status":"OPEN"}' > /dev/null; [ "$(status)" = "200" ]; check "staff opens the queue" $?

curl -s -N -b "$WORK/c" -H 'Accept: text/event-stream' "$BASE/api/queues/$SMOKE_QUEUE/events" > "$WORK/sse.log" &
SSE_PID=$!
sleep 2

TICKET=$(request "$WORK/c" POST "/api/queues/$SMOKE_QUEUE/tickets" | json "d['id']")
[ "$(status)" = "201" ]; check "customer joins as walk-in" $?
for i in 1 2 3; do  # three more customers join at the same moment
  ( request "$WORK/p$i" POST /api/auth/register "{\"email\":\"smoke-$STAMP-$i@example.com\",\"fullName\":\"Parallel $i\",\"password\":\"$PASSWORD\"}" > /dev/null
    request "$WORK/p$i" POST "/api/queues/$SMOKE_QUEUE/tickets" | json "d['tokenNumber']" > "$WORK/token$i" ) &
done
wait $(jobs -p | grep -v "^$SSE_PID$") 2>/dev/null
TOKENS=$(cat "$WORK"/token* | sort -n | tr '\n' ' ')
[ "$(cat "$WORK"/token* | sort -u | wc -l | tr -d ' ')" = "3" ] && ! echo " $TOKENS" | grep -q ' 1 '; check "parallel walk-ins got unique tokens ($TOKENS)" $?

called=$(request "$WORK/staff" POST "/api/staff/queues/$SMOKE_QUEUE/call-next")
[ "$(echo "$called" | json "d['ticketId']")" = "$TICKET" ]; check "call-next calls the first customer" $?
request "$WORK/staff" POST "/api/staff/queues/$SMOKE_QUEUE/call-next" > /dev/null; [ "$(status)" = "409" ]; check "second call-next blocked while counter busy (409)" $?
request "$WORK/staff" POST "/api/staff/tickets/$TICKET/start" | json "d['status']" | grep -q SERVING; check "start service" $?
request "$WORK/staff" POST "/api/staff/tickets/$TICKET/complete" | json "d['status']" | grep -q COMPLETED; check "complete service" $?

sleep 2
kill "$SSE_PID" 2>/dev/null; wait "$SSE_PID" 2>/dev/null
grep -q 'event:ready' "$WORK/sse.log"; check "SSE stream connected" $?
[ "$(grep -c 'event:queue-changed' "$WORK/sse.log")" -ge 4 ]; check "SSE delivered queue-changed events ($(grep -c 'event:queue-changed' "$WORK/sse.log"))" $?
! grep -q "$EMAIL" "$WORK/sse.log"; check "SSE payloads contain no customer data" $?

request "$WORK/c" GET /api/me/notifications | json "any(n['type'] == 'TURN_CALLED' for n in d['items'])" | grep -q True
check "TURN_CALLED notification stored" $?
login "$WORK/c2" "$EMAIL" "$PASSWORD"
request "$WORK/c2" GET /api/me/notifications | json "d['unreadCount'] >= 1" | grep -q True; check "notifications survive re-login" $?

# --- tenant isolation ------------------------------------------------------------------------
login "$WORK/nb" staff@northbridge.example "$DEMO_PASSWORD"
request "$WORK/nb" GET "/api/staff/queues/$SMOKE_QUEUE/board" > /dev/null; [ "$(status)" = "403" ]; check "other organization's staff denied (403)" $?
request "$WORK/nb" GET "/api/tickets/$TICKET" > /dev/null; [ "$(status)" = "404" ]; check "other organization cannot see the ticket (404)" $?

# --- clean up: close the smoke queue --------------------------------------------------------
for t in $(request "$WORK/staff" GET "/api/staff/queues/$SMOKE_QUEUE/board" | json "' '.join(x['ticketId'] for x in d['waiting'])"); do
  request "$WORK/staff" POST "/api/tickets/$t/cancel" > /dev/null
done
request "$WORK/staff" POST "/api/staff/queues/$SMOKE_QUEUE/status" '{"status":"CLOSED"}' > /dev/null

cat > "$STATE_FILE" <<STATE
SMOKE_EMAIL=$EMAIL
SMOKE_PASSWORD=$PASSWORD
SMOKE_TICKET=$TICKET
SMOKE_APPOINTMENT=$APPOINTMENT
STATE

echo
echo "Passed: $PASS  Failed: $FAIL"
echo "Saved smoke customer to .smoke-state; restart the service and run with --after-restart."
[ "$FAIL" -eq 0 ]
