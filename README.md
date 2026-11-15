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
  model/      Vehicle, ParkingSlot, ParkingRecord, VehicleType, User, Role
  dao/        JDBC data-access classes (one per table)
  service/    ParkingService (allocation, billing, reports), AuthService (login, sign-up)
  util/       DBConnection (JDBC connection helper), AuthUtil (password hashing),
              SchemaMigrator (upgrades an older users table automatically)
  web/        Server, ApiHandlers, SessionManager, StaticFileHandler, JsonUtil (REST API + static file server)
  ConsoleApp.java   Text-menu version of the app (same service layer)

src/main/resources/
  db/schema.sql     MySQL schema + seed data (wipes and recreates the tables)
  db/migrate_signup.sql  Optional manual upgrade of an existing database (keeps your data)
  db.properties.example  Template for db.properties (copy it; db.properties is git-ignored)
  static/           The HTML/CSS/JS frontend
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

## Setup

### 1. Create the database

Make sure MySQL is installed and running, then:

```bash
mysql -u root -p < src/main/resources/db/schema.sql
```

This creates the `campus_parking` database and its five tables (users, parking
slots, parking rates, vehicles, parking records), plus 10 sample parking slots,
the billing rates and the two demo accounts.

> **Updating from an earlier version?**
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
| POST   | `/api/park`                      | user   | `{vehicleNumber, vehicleType, ownerName, ownerContact}` -> allocates a slot and parks the vehicle |
| POST   | `/api/unpark`                    | user   | `{vehicleNumber}` -> ends the session, frees the slot, returns duration and fee |
| GET    | `/api/locate?vehicleNumber=..`    | user   | Where a vehicle is parked (students/faculty: own vehicles only) |
| GET    | `/api/parked`                    | user   | Currently parked (students/faculty: own vehicles only) |
| GET    | `/api/history?limit=20`          | user   | Recent history with fees (students/faculty: own only) |
| GET    | `/api/report`                    | admin  | Occupancy report per vehicle type + overall|
| GET    | `/api/rates`                     | user   | Hourly rate per vehicle type               |
| POST   | `/api/admin/slots`               | admin  | `{slotNumber, vehicleType, location}` -> adds a slot |
| DELETE | `/api/admin/slots/{id}`          | admin  | Deletes a slot (not while occupied or once it has history) |
| GET    | `/api/admin/revenue`             | admin  | Total fees collected                       |

## Possible future improvements

- Reservations (book a slot in advance) instead of first-come allocation
- Online payment instead of just calculating the fee
- Admin screen to change the hourly rates and manage user accounts
- Email/SMS notification when a vehicle has been parked for a long time
- Switch the hand-rolled JSON/HTTP layer to Spring Boot if the project grows
