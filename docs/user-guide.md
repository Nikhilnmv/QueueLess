# User guide

This guide covers:
- how to run QueueLess on your own computer
- which demo accounts to use
- what each kind of user can do
- a script for showing it live

For the hosted copy on Render and Neon, see [deployment.md](deployment.md).

## 1. Install the tools (once)

| Tool | Version | Check with | Install on macOS |
|---|---|---|---|
| Java (JDK) | 21 | `java -version` | `brew install openjdk@21`, or any JDK 21 |
| Node.js | 22 or newer | `node -v` | `brew install node` |
| PostgreSQL server | 17 | `initdb --version` | `brew install postgresql@17` |
| Git | any | `git --version` | `xcode-select --install` |

- **Linux:** use the package manager, for example
  `sudo apt install openjdk-21-jdk nodejs npm postgresql`.
- **Windows:** use WSL (Ubuntu) and follow the Linux steps.

If the terminal says `initdb: command not found` after a Homebrew install, add PostgreSQL to your
PATH and open a new terminal:

```bash
echo 'export PATH="$(brew --prefix postgresql@17)/bin:$PATH"' >> ~/.zshrc
```

## 2. Run it

From the project folder:

```bash
scripts/run-local.sh
```

The script prints each of its three steps:
1. It starts a private PostgreSQL database for the project on port 5433. The data lives in
   `.local/`, which Git ignores.
2. It builds the React frontend and the Spring Boot backend into one jar. This happens only the
   first time and takes a minute or two.
3. It starts the app with demo data.

Then open http://localhost:8080. Press Ctrl+C in the terminal to stop the app.

- **After changing code,** run `scripts/run-local.sh --rebuild` so the jar is built again.
- **The database keeps running** after the app stops. Stop it with
  `scripts/local-postgres.sh stop`.
- **For clean demo data,** stop the app and run `scripts/local-postgres.sh reset-dev`. The next
  start seeds the demo data again. Do this before a presentation, because the automated tests add
  test queues and users to the local database.

## 3. Demo accounts

On your computer every demo account uses the password `Password123!`. On the hosted copy, the
password is whatever was set in Render as `QUEUELESS_SEED_PASSWORD`. The login page lists the main
accounts; click one to fill in its email.

| Email | Name | Role |
|---|---|---|
| `asha@example.com` | Asha Demo | visitor |
| `ravi@example.com` | Ravi Demo | visitor |
| `meera@example.com` | Meera Demo | visitor |
| `staff@citycare.example` | City Care Reception | staff at City Care Clinic |
| `admin@citycare.example` | City Care Admin | administrator of City Care Clinic |
| `staff@northbridge.example` | Northbridge Desk | staff at Northbridge College Office |
| `admin@northbridge.example` | Northbridge Admin | administrator of Northbridge College Office |
| `platform@queueless.test` | Platform Operator | platform admin, who creates organizations |

The demo data has two organizations:

| Organization | Counter | Token prefix |
|---|---|---|
| City Care Clinic | General OPD | `G` |
| City Care Clinic | Lab Sample Collection | `L` |
| Northbridge College Office | Admissions Desk | `A` |
| Northbridge College Office | Fee Counter | `F` |

A few sample tickets may already be waiting, so the boards are not empty. Anyone can also create
a visitor account with **Sign up**.

## 4. As a visitor

Log in as `asha@example.com`.

**Take a token (walk-in)**
1. Click **Find a service**, then **City Care Clinic**. The table shows, for each counter:
   - whether it is open
   - how many people are waiting
   - the wait if you join now
2. Click **General OPD**. The **Right now** box shows who is being served and the average service
   time.
3. Click **Get a token**. Your ticket page shows:
   - the token, for example `G-007`
   - your place in line and how many people are ahead
   - the estimated wait

   **Live** at the top right means the page updates by itself.
4. To give up your place, click **Leave the queue** and confirm.

**Book an appointment**
1. On the queue page, click **Choose a time**.
2. Pick a day, then a time. Each time button shows how many places are left. Then click
   **Confirm booking**.
3. The appointment page shows the booking reference (for example `QL-7KX2PQ`) and your reserved
   token. A confirmation also appears under **Notifications**.
4. On the day, from 60 minutes before the slot, the page shows **I have arrived, check me in**.
   Checking in puts you in the line. Staff can also check you in at the desk.
5. **Cancel appointment** frees the slot for someone else.

**My visits** lists three things: tickets in line now, upcoming appointments and past visits.

**Notifications** keeps booking confirmations and the "your turn is near" and "it's your turn"
messages. They stay after you log out.

## 5. As staff

Log in as `staff@citycare.example`, click **Staff**, then **General OPD**. This is the live board.

- **Queue status:**
  - **Open:** visitors can join and staff can call.
  - **Pause:** visitors can still join, but nobody is called until it is opened again.
  - **Close:** nobody can join.
- **Call next** calls the first person in the waiting line. The rule is shown under the table:
  priority first, then check-in time. That person's screen shows "It's your turn!" straight away.
- **Start service** when they reach the counter, then **Complete** when done. Use **No-show** if
  they do not come.
- **Priority:** the menu in each row moves someone up (High) or down (Low).
- **Register a walk-in** gives a token to someone without an account. Enter their name.
- **Expected today** lists booked visitors who have not arrived yet:
  - **Check in** puts them in the line.
  - **Cancel** cancels their booking.
- **Today** shows the day's counts and the average service time used for the estimates.

## 6. As an organization administrator

Log in as `admin@citycare.example`, click **Admin**, then **City Care Clinic**.

- **Queues:**
  - **New queue** opens a form: name, token prefix, opening hours, slot length, bookings per slot
    and typical service time.
  - **Edit** changes a queue.
  - **Board** opens its live board. Administrators can do everything staff can.
- **Members:** add a registered user by email as Staff or Admin, change roles, or remove members.
  The last administrator cannot be demoted or removed.
- **Profile:** the organization's name and description.

A new queue starts closed. Open it from its board before visitors can join.

## 7. As the platform admin

Log in as `platform@queueless.test` and click **Platform**. **New organization** creates an
organization (name, web address, time zone) and makes an existing user its first administrator.
The platform admin cannot see any organization's queues or visitors.

## 8. A demo for the presentation (about 8 minutes)

**Before you start:**
1. Reset the demo data and start the app (section 2).
2. Open two browser windows side by side, one of them private, so each can log in as a different
   user. Call them A and B.

| Step | Window | Do this | Point out |
|---|---|---|---|
| 1 | A | Home, then Find a service, then City Care Clinic | live waiting numbers for each counter |
| 2 | A | Log in as Asha, open General OPD, click Get a token | token, place in line, estimate, "Live" |
| 3 | B | Log in as `staff@citycare.example`, then Staff, then General OPD; copy the page address | Asha's token in the waiting table |
| 4 | B | Set Asha's priority to High | she moves to the top in both windows without reloading |
| 5 | B | Call next | Asha's window shows "It's your turn!" and a notification |
| 6 | B | Start service, then Complete | Asha's ticket says "Service completed"; the Today counts change |
| 7 | A | Book a slot for tomorrow, then cancel it | booking reference, reserved token, slot freed |
| 8 | B | Log out, log in as `staff@northbridge.example`, paste the City Care board address | the board refuses: another organization's staff have no access |
| 9 | B | Log in as `admin@citycare.example`, then Admin | create a queue, add a member, try to demote the last admin |
| 10 | – | Show `ConcurrencyIT` and diagram 04 | 30 simultaneous walk-ins get tokens 1 to 30, never a duplicate |

## 9. Running the tests

The integration tests need the database from section 2 to be running.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS; elsewhere point JAVA_HOME at a JDK 21
cd backend && ./mvnw verify                         # 70 unit + 70 integration and concurrency tests
cd ../frontend && npm ci && npm run build           # type check and production build
cd ../backend && ./mvnw -DskipTests package         # jar with the React build inside
cd ../frontend && npx playwright install chromium && npx playwright test   # 6 browser tests
```

`docs/testing.md` explains what each suite covers.

## 10. Using the hosted copy

The hosted copy runs on Render's free plan, which sleeps after 15 minutes without visitors. The
first request after that takes about a minute while it wakes up, so open the site a few minutes
before you present. The data lives in Neon PostgreSQL and survives restarts and redeploys.

## 11. Troubleshooting

| Problem | Fix |
|---|---|
| `Port 8080 is already in use` | Another copy is running. Stop it with Ctrl+C in its terminal, or find it with `lsof -i :8080`. |
| `Unsupported class file major version` or a Java version error | Use JDK 21: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`. |
| `initdb: command not found` | PostgreSQL is not on your PATH; see section 1. |
| The page still shows old screens after a code change | Run `scripts/run-local.sh --rebuild`. |
| The top right shows "Reconnecting…" | The app restarted or the network dropped. The page reconnects by itself and then reloads its data. |
| Login says the email or password is wrong | Local demo accounts use `Password123!`. After 5 wrong attempts an email is locked for 15 minutes. |
