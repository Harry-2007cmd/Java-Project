# Smart Campus Parking and Vehicle Finder

A Java mini-project that manages campus parking availability and helps
students locate their parked vehicles.

**Features**
- Display available parking slots (filterable by vehicle type)
- Allocate a slot automatically based on vehicle type (Car / Bike / Bus / EV)
- Store parking location and vehicle details
- Calculate how long a vehicle has been / was parked
- Generate occupancy reports (per vehicle type and overall)

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
  model/      Vehicle, ParkingSlot, ParkingRecord, VehicleType
  dao/        JDBC data-access classes (one per table)
  service/    ParkingService - all business logic (allocation, duration, reports)
  util/       DBConnection - JDBC connection helper
  web/        Server, ApiHandlers, StaticFileHandler, JsonUtil (REST API + static file server)
  ConsoleApp.java   Text-menu version of the app (same service layer)

src/main/resources/
  db/schema.sql     MySQL schema + seed data
  db.properties     DB connection settings
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

This creates the `campus_parking` database, its three tables, and 10 sample
parking slots so the app has something to show immediately.

### 2. Configure the connection

Edit `src/main/resources/db.properties` with your MySQL username/password:

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

## REST API reference

| Method | Endpoint                         | Description                              |
|--------|-----------------------------------|-------------------------------------------|
| GET    | `/api/slots`                     | All parking slots                          |
| GET    | `/api/slots?type=CAR`             | Only free slots of that vehicle type       |
| POST   | `/api/park`                      | `{vehicleNumber, vehicleType, ownerName, ownerContact}` -> allocates a slot and parks the vehicle |
| POST   | `/api/unpark`                    | `{vehicleNumber}` -> ends the parking session, frees the slot |
| GET    | `/api/locate?vehicleNumber=..`    | Finds where a vehicle is currently parked  |
| GET    | `/api/parked`                    | Everything currently parked                |
| GET    | `/api/history?limit=20`          | Recent park/unpark history                 |
| GET    | `/api/report`                    | Occupancy report per vehicle type + overall|

## Possible future improvements

- Login for students/admin, and a separate admin view for managing slots
- Reservations (book a slot in advance) instead of first-come allocation
- Billing/fees based on parking duration
- Email/SMS notification when a vehicle has been parked for a long time
- Switch the hand-rolled JSON/HTTP layer to Spring Boot if the project grows
