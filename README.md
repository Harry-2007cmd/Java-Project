# Smart Campus Parking and Vehicle Finder

A Java mini-project that manages campus parking availability and helps
students locate their parked vehicles.

**Features**
- Display available parking slots (filterable by vehicle type)
- Allocate a slot automatically based on vehicle type (Car / Bike / Bus / EV)
- Store parking location and vehicle details
- Calculate how long a vehicle has been / was parked
- Generate occupancy reports (per vehicle type and overall)
- **Login with three roles**: students and faculty see and manage only their own
  vehicles; admins see everything and can add/delete parking slots and view total revenue
- **Sign-up for students and faculty**: pick Student or Faculty, give a name, email,
  username and password (students also give a roll number)
- **Billing by duration**: a fee is calculated when a vehicle leaves

## Documentation and presentation

| File | What it is |
|------|------------|
| [`docs/PROJECT_DOCUMENTATION.md`](docs/PROJECT_DOCUMENTATION.md) | Detailed documentation focused on the Java concepts, JDBC, architecture, security and the key flows, with 37 viva questions and model answers |
| [`docs/Smart-Campus-Parking-Presentation.pptx`](docs/Smart-Campus-Parking-Presentation.pptx) | 13-slide presentation with speaker notes |

### Demo accounts

| Username   | Password     | Role    |
|------------|--------------|---------|
| `admin`    | `admin123`   | ADMIN   |
| `student1` | `student123` | STUDENT |
| `faculty1` | `faculty123` | FACULTY |

New students and faculty can sign up from the login screen. Sign-up only ever
creates a STUDENT or FACULTY account (the server rejects anything else); admins
are created directly in the database.

| Sign-up field | Student | Faculty | Rules |
|---------------|:-------:|:-------:|-------|
| Full name     | yes     | yes     | up to 100 characters |
| Email         | yes     | yes     | valid address, unique (case-insensitive) |
| Roll no       | yes     | -       | 3-30 characters: letters, digits, `-`, `/`, `_`; unique |
| Username      | yes     | yes     | 3-30 characters: letters, digits, `_`, `.`; unique |
| Password      | yes     | yes     | at least 6 characters (confirmed on the form) |

On the login screen, "Remember me" only remembers the username in the browser
(never the password). "Forgot Password?" and "Continue with Google" are part of
the design but not connected to a backend yet, so they just show a message.

### Billing rule

The hourly rate for the vehicle type (table `parking_rates`; default Car 20,
Bike 10, Bus 50, EV 15 rupees) is charged for **every started hour, minimum one
hour**. At 20/hour: 5 min = 20, 60 min = 20, 61 min = 40. The fee is stored on
the parking record when the vehicle exits.

### Security notes

- Passwords are never stored in plain text: each user has a random salt and the
  password is hashed with PBKDF2-HMAC-SHA256 (`AuthUtil`).
- Login sessions are kept in server memory and identified by a random cookie,
  so restarting the server logs everyone out.
- A student or faculty member cannot see, find or un-park anyone else's vehicle;
  the server enforces this, not just the UI.
- Everything shown in the web page is HTML-escaped.

## Tech stack

| Layer      | Choice                                                            |
|------------|--------------------------------------------------------------------|
| Language   | Java 17                                                           |
| Build tool | Maven                                                             |
| Database   | MySQL, accessed with plain JDBC (no ORM)                          |
| Backend    | Java's built-in `com.sun.net.httpserver.HttpServer` serving a JSON REST API |
| Frontend   | Static HTML, CSS and vanilla JavaScript (`fetch`) - no frameworks |
| Fallback UI| A text-menu console app using the exact same service layer       |

There is only **one external dependency**: the MySQL JDBC driver
(`mysql-connector-j`). Everything else — the HTTP server, JSON handling,
routing — is written by hand so the whole project stays easy to explain.

## Project structure

```
src/main/java/com/campus/parking/
  model/      Vehicle, ParkingSlot, ParkingRecord, VehicleType (enum), User, Role (enum)
  dao/        JDBC data-access classes, one per table: ParkingSlotDAO, VehicleDAO,
              ParkingRecordDAO, ParkingRateDAO, UserDAO, plus DashboardDAO (admin aggregates)
  service/    ParkingService (allocation, billing, reports), AuthService (login, sign-up)
  util/       DBConnection (JDBC connection helper), AuthUtil (PBKDF2 password hashing),
              SchemaMigrator (upgrades an older database automatically)
  web/        Server, ApiHandlers, SessionManager, StaticFileHandler, JsonUtil, AuthException
              (REST API + static file server)
  ConsoleApp.java   Text-menu version of the app (same service layer)

src/main/resources/
  db/schema.sql          MySQL schema + seed data (wipes and recreates the tables)
  db/demo_data.sql       Optional month of demo history for the admin dashboard
  db/migrate_signup.sql  Optional manual upgrade: email, roll_no and FACULTY role (keeps your data)
  db/migrate_admin.sql   Optional manual upgrade: parking_slots.out_of_service (keeps your data)
  db.properties.example  Template for db.properties (copy it; db.properties is git-ignored)
  static/                The HTML/CSS/JS frontend (Three.js bundled for the 3D map)

tools/gen_demo_data.py   Generates db/demo_data.sql
docs/                    Documentation and presentation (see above)
```

## How it works

- **parking_slots** holds every physical slot (number, vehicle type, location, occupied flag).
- **vehicles** holds every vehicle the system has seen (number, type, owner).
- **parking_records** is the log of park/unpark events: one row per session,
  with `entry_time` set immediately and `exit_time` / `duration_minutes`
  filled in only when the vehicle leaves. A record with no `exit_time` is
  how the app knows a vehicle is *currently* parked - and is exactly what
  "find my vehicle" and the occupancy numbers are built from.
- Allocating a slot and creating a parking record happen inside a single
  JDBC transaction (`ParkingService.parkVehicle`), so two people parking at
  the same moment can never be given the same slot.

## Java concepts used

A quick map from concept to code (the full explanation is in
[`docs/PROJECT_DOCUMENTATION.md`](docs/PROJECT_DOCUMENTATION.md)).

| Area | Concepts | Where |
|------|----------|-------|
| OOP | Encapsulation, inheritance, polymorphism, abstraction, nested classes, static utility classes | `ParkingRecord`, `AuthException extends RuntimeException`, route classes implementing `HttpHandler`, `ParkingService.OccupancyStat`, `AuthUtil` |
| Modern Java | Enums with methods, records, switch expressions, text blocks, `var`, `Optional`, lambdas, functional interfaces, `HexFormat` | `VehicleType`, `SessionManager.Session`, `ConsoleApp`, `ParkingService.locateVehicle`, `ParkingRecordDAO.closeRecord`, `ApiHandlers.HandlerBody` |
| Collections | `List`/`ArrayList`, `Map`, `LinkedHashMap`, `ConcurrentHashMap` | DAO results, `occupancyReport`, `JsonUtil`, `SessionManager` |
| Exceptions | Checked (`SQLException`) and unchecked exceptions, custom exception, try-with-resources, multi-catch, `finally`, exception chaining | DAOs, `ParkingService`, `AuthUtil`, `ApiHandlers.safely` |
| JDBC | `Connection`, `PreparedStatement`, `ResultSet`, transactions (`commit`/`rollback`), `SELECT ... FOR UPDATE`, generated keys | `DBConnection`, all DAOs, `ParkingService.parkVehicle` |
| Concurrency | Fixed thread pool, thread-safe map, `synchronized`, database row locks | `Server`, `SessionManager`, `SchemaMigrator`, `ParkingSlotDAO.allocateFreeSlot` |
| Other | `BigDecimal` for money, `java.time`, regular expressions, streams (I/O), `StringBuilder` | billing, `ParkingRecord`, `AuthService`, `StaticFileHandler`, `JsonUtil` |

## Setup

### 1. Create the database

Make sure MySQL is installed and running, then:

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

This creates the `campus_parking` database and its five tables (users, parking
slots, parking rates, vehicles, parking records), plus a 58-slot campus layout
(Turing Block, Gate 3, Picasso Block rear, two-wheeler parking, bus bay, EV zone),
the billing rates and the demo accounts.

### 1b. (Optional) Load demo data for the admin dashboard

```bash
mysql -u root -p < src/main/resources/db/demo_data.sql
```

Adds the campus slots (if missing), about a month of parking history, vehicles
parked right now (some overstaying, one zone nearly full) and two slots closed
for maintenance, so the charts and the 3D map look alive. It is safe to run again:
it only replaces rows it created itself (vehicles with contact `DEMO`). The file is
generated by `python3 tools/gen_demo_data.py`; regenerate it if you load it long
after generating it so the weekday pattern lines up.

> **Updating from an earlier version?**
> - *From the previous version (before the admin dashboard):* nothing to do. The app
>   adds the new `parking_slots.out_of_service` column by itself on first use. (If
>   your MySQL user may not `ALTER` tables, run `src/main/resources/db/migrate_admin.sql` once.)
> - *From the version with login + billing:* nothing to do. The app adds the new
>   `email` / `roll_no` columns and the `FACULTY` role to your existing `users`
>   table by itself the first time it starts, and keeps all your data. (If your
>   MySQL user may not `ALTER` tables, the app prints a warning; then run
>   `src/main/resources/db/migrate_signup.sql` once.) The demo `faculty1` account
>   is only created by `schema.sql`; sign up a faculty account instead if you
>   don't want to re-run it.
> - *From anything older:* run `schema.sql` again. It drops and recreates the
>   tables, so any parking data you entered before is wiped. You can also open it
>   in MySQL Workbench (File > Open SQL Script) and press the lightning-bolt
>   Execute button.

### 2. Configure the connection

Copy `src/main/resources/db.properties.example` to `src/main/resources/db.properties`
(the real file is git-ignored so your password is never committed) and put your
MySQL username/password in it:

```properties
db.url=jdbc:mysql://localhost:3306/campus_parking?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC
db.user=root
db.password=your_password
```

(You can also override these with the `DB_URL`, `DB_USER`, `DB_PASSWORD`
environment variables instead of editing the file.)

### 3. Build

```bash
mvn clean package
```

This downloads the MySQL driver and produces a runnable jar at
`target/smart-campus-parking.jar`.

### 4. Run - web UI (recommended)

```bash
mvn exec:java -Dexec.mainClass=com.campus.parking.web.Server
# or, after `mvn package`:
java -jar target/smart-campus-parking.jar
```

Then open **http://localhost:8080** in your browser. Change the port with
`-Dserver.port=9090` or the `PORT` environment variable if 8080 is busy.

### 5. Run - console UI (quick demo / viva, no browser needed)

```bash
mvn exec:java -Dexec.mainClass=com.campus.parking.ConsoleApp
```

It asks for a username and password first (use a demo account above).

## Admin dashboard

Admins supervise only, so they have no *Park a vehicle* / *Find my vehicle* screens
(the API refuses those calls for admins with `403`). They get:

- **Dashboard** - summary cards (slots, occupied, available, occupancy %, today's revenue),
  alerts (zone 90%+ full, no free slots of a type, vehicles parked over 8 h, slots under
  maintenance), occupancy by zone and by vehicle type, revenue (today / 7 days / 30 days,
  14-day chart, by vehicle type), peak hours and busiest weekdays, and a live table of
  parked vehicles with overstays highlighted. Charts are plain SVG, no chart library.
- **3D parking map** - an interactive Three.js scene of the campus (drag to rotate, scroll
  to zoom). Green = free, red = occupied (with a car, bike, bus or EV on it), grey with cones =
  closed for maintenance, pulsing amber = overstay. Click a slot to see the vehicle, owner and
  time parked, and to close or reopen it. Three.js is bundled in `static/js/vendor`, so it
  works offline.
- **Manage slots** - add/delete slots and close a free slot for maintenance. Closed slots are
  never allocated and do not count as capacity.

> The zone names come from the parking areas Chitkara University lists publicly. The
> geometry and slot counts are an illustrative layout, **not** a surveyed plan. A zone name the
> map does not recognise is placed automatically in an extra row, so new slots always appear.

## REST API reference

Every endpoint except login/sign-up needs a logged-in session (cookie). Without
one the API answers `401`; a student or faculty member calling an admin endpoint gets `403`.

| Method | Endpoint                         | Who    | Description                              |
|--------|-----------------------------------|--------|-------------------------------------------|
| POST   | `/api/login`                     | anyone | `{username, password}` -> starts a session |
| POST   | `/api/register`                  | anyone | `{role, fullName, email, username, password, rollNo}` -> creates a STUDENT or FACULTY account and logs in (`rollNo` required for students only; `ADMIN` is rejected) |
| POST   | `/api/logout`                    | user   | Ends the session                           |
| GET    | `/api/me`                        | user   | Who is logged in (username, name, role, email, roll no) |
| GET    | `/api/slots`                     | user   | All parking slots                          |
| GET    | `/api/slots?type=CAR`             | user   | Only free slots of that vehicle type       |
| POST   | `/api/park`                      | student/faculty | `{vehicleNumber, vehicleType, ownerName, ownerContact}` -> allocates a slot and parks the vehicle |
| POST   | `/api/unpark`                    | user   | `{vehicleNumber}` -> ends the session, frees the slot, returns duration and fee |
| GET    | `/api/locate?vehicleNumber=..`    | user   | Where a vehicle is parked (students/faculty: own vehicles only) |
| GET    | `/api/parked`                    | user   | Currently parked (students/faculty: own vehicles only) |
| GET    | `/api/history?limit=20`          | user   | Recent history with fees (students/faculty: own only) |
| GET    | `/api/report`                    | admin  | Occupancy report per vehicle type + overall|
| GET    | `/api/rates`                     | user   | Hourly rate per vehicle type               |
| POST   | `/api/admin/slots`               | admin  | `{slotNumber, vehicleType, location}` -> adds a slot |
| DELETE | `/api/admin/slots/{id}`          | admin  | Deletes a slot (not while occupied or once it has history) |
| GET    | `/api/admin/revenue`             | admin  | Total fees collected                       |
| GET    | `/api/admin/dashboard`           | admin  | Summary, occupancy by zone/type, revenue, peak hours, overstays and alerts in one call |
| POST   | `/api/admin/maintenance`         | admin  | `{slotId, outOfService: "true"/"false"}` -> closes/reopens a free slot |

## Possible future improvements

- Reservations (book a slot in advance) instead of first-come allocation
- Online payment instead of just calculating the fee
- Admin screen to change the hourly rates and manage user accounts
- Email/SMS notification when a vehicle has been parked for a long time
- Switch the hand-rolled JSON/HTTP layer to Spring Boot if the project grows
