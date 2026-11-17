# Smart Campus Parking and Vehicle Finder - Project Documentation

A Java 17 and MySQL application that allocates campus parking slots by vehicle type, finds where a vehicle is parked, bills by duration and reports occupancy. This document explains the code and the Java concepts behind it, for the viva.

## 1. Project overview

The project manages campus parking: students and faculty park and find their own vehicles, and admins supervise slots, revenue and occupancy. It is plain Java 17 with MySQL over JDBC, built with Maven, and its only external dependency is the MySQL driver.

| Feature | Where it lives |
| --- | --- |
| Display available slots (filter by vehicle type) | `ParkingSlotDAO.findAvailable`, `GET /api/slots` |
| Allocate a slot by vehicle type (CAR, BIKE, BUS, EV) | `ParkingSlotDAO.allocateFreeSlot` |
| Store vehicle details and parking location | `VehicleDAO`, `parking_records` table |
| Calculate parking duration | `ParkingRecordDAO.closeRecord` (MySQL `TIMESTAMPDIFF`) |
| Billing by started hour | `ParkingService.calculateFee` |
| Occupancy reports and admin dashboard | `ParkingService.occupancyReport`, `DashboardDAO` |
| Login and sign-up (student, faculty, admin) | `AuthService`, `AuthUtil`, `SessionManager` |
| Vehicle finder | `ParkingService.locateVehicle` |

| Layer | Technology |
| --- | --- |
| Language | Java 17 |
| Build | Maven (shade plugin builds one runnable jar) |
| Database | MySQL via plain JDBC, no ORM |
| Backend | JDK built-in `com.sun.net.httpserver.HttpServer`, JSON REST API |
| Frontend | Static HTML, CSS and vanilla JavaScript, Three.js for the 3D map |
| Second interface | Text-menu `ConsoleApp` using the same service layer |

## 2. Architecture

The code is a layered design under `com.campus.parking`: each layer only talks to the one below it, so the business rules do not depend on HTTP or on SQL.

| Package | Role | Classes |
| --- | --- | --- |
| `web` | Translates HTTP to Java calls, checks who is logged in | `Server`, `ApiHandlers`, `SessionManager`, `StaticFileHandler`, `JsonUtil`, `AuthException` |
| `service` | Business rules and transactions | `ParkingService`, `AuthService` |
| `dao` | One class per table, all SQL lives here | `ParkingSlotDAO`, `VehicleDAO`, `ParkingRecordDAO`, `ParkingRateDAO`, `UserDAO`, `DashboardDAO` |
| `model` | Plain data objects | `ParkingSlot`, `Vehicle`, `ParkingRecord`, `User`, `VehicleType`, `Role` |
| `util` | Shared helpers | `DBConnection`, `AuthUtil`, `SchemaMigrator` |
| (root) | Console entry point | `ConsoleApp` |

A request such as parking a vehicle flows like this:

1. The browser sends `POST /api/park` with a JSON body and its session cookie.
2. `Server` routes it to `ApiHandlers.ParkHandler`, which calls `SessionManager.requireLogin` and rejects admins.
3. `ParkHandler` calls `ParkingService.parkVehicle`, which opens one JDBC transaction.
4. The service calls `VehicleDAO`, `ParkingSlotDAO` and `ParkingRecordDAO`, which run the SQL.
5. The service commits and returns a `ParkingRecord`; `JsonUtil` turns it into JSON for the browser.

The same `ParkingService` is used by `ConsoleApp`, which shows the logic is not tied to the web layer.

## 3. Java concepts used in the project

Every concept below appears in the actual code; the last column is where to point if the examiner asks "show me".

### Object-oriented programming

| Concept | What it means | Where it is used |
| --- | --- | --- |
| Class and object | A class is a blueprint, an object is one instance of it | `ParkingSlot`, `Vehicle`, `ParkingRecord`, `User` model one table row each |
| Encapsulation | Fields are `private`, access only through getters and setters | `ParkingRecord` keeps `entryTime`, `feeAmount` and others private |
| Constructors and overloading | Several constructors with different parameters | `User` has a 6-argument and an 8-argument constructor; the shorter one calls `this(...)` |
| Abstraction | Callers use a simple method and ignore the SQL or HTTP detail | `ParkingService.parkVehicle` hides allocation, ownership and record creation |
| Inheritance | A class extends another | `AuthException extends RuntimeException` and adds an HTTP status |
| Polymorphism (interface) | One interface, many implementations | Each handler in `ApiHandlers` implements `HttpHandler.handle`; `StaticFileHandler` is one more |
| Static members | Belong to the class, not an object | `ParkingService.calculateFee` is `static` so it is testable without a database |
| Utility classes | `final` class, private constructor, static methods | `DBConnection`, `AuthUtil`, `SessionManager`, `SchemaMigrator`, `JsonUtil` |
| Nested and inner classes | A class declared inside another | `ParkingService.OccupancyStat`; every route class inside `ApiHandlers` |
| Package structure | Groups related classes, controls visibility | `model`, `dao`, `service`, `util`, `web` |

### Modern Java (Java 8 to 17 features)

| Feature | Example in the code |
| --- | --- |
| Enum with methods | `VehicleType { CAR, BIKE, BUS, EV }` with `fromString(...)` that parses case-insensitively and gives a friendly error; `Role` is also an enum |
| Records (Java 16) | `SessionManager.Session` and `DashboardDAO.Occupancy` are one-line immutable data carriers; `Occupancy` also has methods like `usable()` and `percent()` |
| Switch expressions (Java 14) | `ConsoleApp` uses `case "1" -> showAvailableSlots();` and `JsonUtil.quote` uses `case '"' -> ...` |
| Text blocks (Java 15) | `ConsoleApp.printMenu` prints the whole menu from one `""" ... """` string |
| `var` (Java 10) | `var vehicle = vehicleDAO.findOrCreate(...)` in `ParkingService` |
| `Optional` | Methods that may find nothing return `Optional<ParkingRecord>` instead of `null`, e.g. `locateVehicle`, `findActiveByVehicleNumber`, `findByUsername` |
| Lambdas | `.filter(r -> ownedBy(r, userId))` and `minutes -> calculateFee(rate, minutes)` |
| Functional interfaces | `IntFunction<BigDecimal>` is passed into `ParkingRecordDAO.closeRecord`; `HandlerBody` is the project's own `@FunctionalInterface` |
| Method chaining (builder style) | `new JsonObject().put("slotId", ...).put("slotNumber", ...)` |
| `HexFormat` (Java 17) | `AuthUtil` converts salt and hash bytes to hex strings |

### Collections and generics

| Type | Where and why |
| --- | --- |
| `List<ParkingSlot>`, `ArrayList` | Results of every `findAll`, `findAvailable`, `findHistory` query |
| `Map<VehicleType, BigDecimal>` | Hourly rate per vehicle type (`ParkingRateDAO.findAll`) |
| `LinkedHashMap` | `occupancyReport` and `JsonObject` keep insertion order so JSON and reports are stable |
| `ConcurrentHashMap` | `SessionManager.SESSIONS`, because eight server threads read and write it at once |
| `Optional<T>`, `List<Object[]>` | Generic return types; `occupancySummary` returns rows as `Object[]` |
| Enhanced `for` loop | `for (Object[] row : slotDAO.occupancySummary())` in `ParkingService.occupancyReport` |

### Exception handling

| Concept | Where it is used |
| --- | --- |
| Checked exception (`SQLException`) | Declared with `throws` on every DAO and service method |
| Unchecked exceptions | `IllegalArgumentException` for bad input (HTTP 400), `IllegalStateException` for rule violations such as "no free slot" (HTTP 409) |
| Custom exception | `AuthException(status, message)` gives 401 or 403 |
| try, catch, finally | `parkVehicle` catches any failure, calls `con.rollback()`, rethrows, and `finally` restores auto-commit |
| try-with-resources | Every `Connection`, `PreparedStatement` and `ResultSet` is closed automatically, even on error |
| Multi-catch | One catch block handles several exception types: NoSuchAlgorithmException and InvalidKeySpecException together in AuthUtil.hash; ConsoleApp catches IllegalArgumentException and IllegalStateException together |
| Catching `Throwable` | `ApiHandlers.safely` also catches `Error`s such as `ExceptionInInitializerError` so every request gets an HTTP reply |
| Exception chaining | `throw new RuntimeException("Could not hash password", e)` keeps the original cause |

### I/O, strings, time and numbers

| Area | Where it is used |
| --- | --- |
| Resource loading | `DBConnection` reads `db.properties` with `getResourceAsStream` into `java.util.Properties` |
| Environment variables | `DB_URL`, `DB_USER`, `DB_PASSWORD`, `PORT` via `System.getenv` override the file |
| Streams (I/O) | `StaticFileHandler` reads a file with `InputStream.readAllBytes` and writes with `OutputStream` |
| `StringBuilder` | `JsonUtil` builds JSON text without creating many temporary strings |
| Regular expressions | `AuthService` validates username, email and roll number with `Pattern.compile` |
| `java.time` | `LocalDateTime` for entry and exit time, `DateTimeFormatter` for `yyyy-MM-dd HH:mm:ss` |
| `BigDecimal` | Money is never a `double`; `ratePerHour.multiply(BigDecimal.valueOf(hours))` |
| Wrapper classes and null | `Long durationMinutes` and `Integer ownerUserId` can be `null`, which a primitive cannot; `rs.wasNull()` detects SQL NULL |

### Concurrency

| Concept | Where it is used |
| --- | --- |
| Thread pool | `server.setExecutor(Executors.newFixedThreadPool(8))` handles requests in parallel |
| Thread-safe collection | `ConcurrentHashMap` for sessions |
| `synchronized` | `SchemaMigrator.ensureUserColumns` is `static synchronized`, so the migration runs once even with parallel requests |
| Database-level locking | `SELECT ... FOR UPDATE` inside a transaction stops two users getting the same slot |

## 4. JDBC and database design

JDBC (Java Database Connectivity) is the standard Java API for talking to a relational database. The project uses it directly, with no ORM, so every SQL statement is visible in the code.

### How a connection is made

`DBConnection` loads the MySQL driver with `Class.forName("com.mysql.cj.jdbc.Driver")` in a `static` initializer, reads the URL, user and password from `db.properties` (or the `DB_URL`, `DB_USER`, `DB_PASSWORD` environment variables), and returns `DriverManager.getConnection(url, user, password)`. Keeping this in one class means the credentials are never hard-coded elsewhere, and `db.properties` is git-ignored so the password is never committed.

### JDBC building blocks used

| JDBC type | Purpose | Example in the code |
| --- | --- | --- |
| `Connection` | One session with the database | `DBConnection.getConnection()` |
| `Statement` | Runs a fixed SQL string with no parameters | `ParkingSlotDAO.findAll`, `occupancySummary` |
| `PreparedStatement` | Pre-compiled SQL with `?` placeholders | Every query that takes user input, e.g. `findAvailable` |
| `ResultSet` | The rows a query returns, read with `rs.next()` | `map(rs)` turns one row into a `ParkingSlot` |
| `executeQuery` and `executeUpdate` | SELECT versus INSERT, UPDATE, DELETE | `ParkingRecordDAO.closeRecord` uses `executeUpdate` |
| `RETURN_GENERATED_KEYS` | Gets the auto-increment id after an insert | `ParkingSlotDAO.insert`, `ParkingRecordDAO.insertEntry` |
| `wasNull()` | Detects SQL NULL after reading a number | `exit_time`, `duration_minutes`, `user_id` mapping |
| `SQLIntegrityConstraintViolationException` | Duplicate or foreign-key violation | A duplicate slot number becomes a friendly "already exists" message |

### Why PreparedStatement

A `PreparedStatement` sends the SQL and the values separately, so user input can never change the meaning of the query. This prevents SQL injection. The one place the code builds SQL by concatenation, `DashboardDAO.occupancyBy(column)`, only ever receives two constants from the class itself, never user input, and a comment in the code says so.

### Transactions

Parking a vehicle changes two tables: it marks the slot occupied and inserts a `parking_records` row. If only one change were saved, the data would be wrong. `ParkingService.parkVehicle` therefore turns off auto-commit, runs all the DAO calls on the same `Connection`, then calls `commit()`. Any exception triggers `rollback()`, and `finally` restores auto-commit. This is the ACID idea of atomicity: all steps happen or none do. `unparkVehicle` uses the same pattern.

### Preventing double allocation

`ParkingSlotDAO.allocateFreeSlot` selects the first free slot with `SELECT ... LIMIT 1 FOR UPDATE`. That locks the row until the transaction ends, so two people parking at the same moment cannot be handed the same slot.

### Database tables

| Table | Key columns | Notes |
| --- | --- | --- |
| `users` | `user_id` (PK), `username` (unique), `password_hash`, `salt`, `role`, `email`, `roll_no` | Role is an ENUM of STUDENT, ADMIN, FACULTY |
| `parking_slots` | `slot_id` (PK), `slot_number` (unique), `vehicle_type`, `location`, `is_occupied`, `out_of_service` | 58 seeded slots across six zones |
| `parking_rates` | `vehicle_type` (PK), `rate_per_hour` | CAR 20, BIKE 10, BUS 50, EV 15 rupees |
| `vehicles` | `vehicle_id` (PK), `vehicle_number` (unique), `user_id` (FK to users) | A vehicle belongs to the account that parked it |
| `parking_records` | `record_id` (PK), `vehicle_id` (FK), `slot_id` (FK), `entry_time`, `exit_time`, `duration_minutes`, `fee_amount` | One row per parking session; `exit_time` NULL means still parked |

The central idea: a record with no `exit_time` means the vehicle is parked right now. The vehicle finder, the currently-parked list and the occupancy numbers are all built from that one rule. Entry and exit times use MySQL's own `NOW()` and `TIMESTAMPDIFF`, so both come from the same clock.

### Schema migration

`SchemaMigrator` upgrades an older database without wiping it. It checks `INFORMATION_SCHEMA.COLUMNS` and runs `ALTER TABLE` only if a column such as `email`, `roll_no` or `out_of_service` is missing. It is `synchronized` and uses a `done` flag, so it runs once per JVM.

## 5. Web layer: HTTP server, REST API and JSON

The project deliberately avoids Spring. `Server.main` uses the JDK's built-in `com.sun.net.httpserver.HttpServer`, so the whole web layer is plain Java that can be explained line by line.

### Server setup

1. `HttpServer.create(new InetSocketAddress(port), 0)` opens a port (default 8080, or the `PORT` variable, or `-Dserver.port`).
2. `server.createContext("/api/park", new ApiHandlers.ParkHandler())` maps a URL path to a handler object. This is the routing table.
3. `server.createContext("/", new StaticFileHandler("static", "index.html"))` serves the HTML, CSS and JavaScript for every other path.
4. `server.setExecutor(Executors.newFixedThreadPool(8))` lets eight requests run at once.
5. `server.start()` begins listening.

### Handlers

Each route is a class that implements `HttpHandler` and overrides `handle(HttpExchange)`. The handler checks the HTTP method (answering 405 for the wrong one), checks the session, reads the JSON body, calls `ParkingService`, and writes a JSON reply. The handlers hold no business logic.

| Method | Endpoint | Who | Purpose |
| --- | --- | --- | --- |
| POST | `/api/login`, `/api/register`, `/api/logout` | anyone, user | Start and end a session, create a student or faculty account |
| GET | `/api/me` | user | Who is logged in |
| GET | `/api/slots[?type=CAR]` | user | All slots, or free slots of one type |
| POST | `/api/park` | student, faculty | Allocate a slot and start a parking record |
| POST | `/api/unpark` | student, faculty | End the session, free the slot, return duration and fee |
| GET | `/api/locate?vehicleNumber=..` | student, faculty | Vehicle finder |
| GET | `/api/parked`, `/api/history` | user | Currently parked and past sessions (own vehicles only unless admin) |
| GET | `/api/report`, `/api/admin/dashboard`, `/api/admin/revenue` | admin | Occupancy report, dashboard, revenue |
| POST, DELETE | `/api/admin/slots`, `/api/admin/maintenance` | admin | Add, delete, close or reopen slots |

### Status codes used

| Code | Meaning | Raised by |
| --- | --- | --- |
| 200, 201 | OK, created | Normal replies; 201 after park and register |
| 400 | Bad input | `IllegalArgumentException` |
| 401 | Not logged in | `AuthException` from `requireLogin` |
| 403 | Not allowed | `AuthException` from `requireAdmin` or `denyAdmin` |
| 405 | Wrong HTTP method | Each handler's method check |
| 409 | Conflict with a rule | `IllegalStateException`, e.g. vehicle already parked or no free slot |
| 500 | Server or database error | `SQLException` or any other `Throwable` |

### Sessions and cookies

HTTP is stateless, so `SessionManager` remembers who is logged in. On login it generates a random 32-byte token with `SecureRandom`, stores `token -> Session` in a `ConcurrentHashMap`, and sends it as a cookie: `Set-Cookie: SID=...; Path=/; HttpOnly; SameSite=Strict`. On every later request `readToken` finds the `SID` cookie and looks the session up. Sessions are held in memory, so restarting the server logs everyone out, which is acceptable for a mini project.

### JSON without a library

`JsonUtil` is a small hand-written JSON builder and parser. `JsonObject` and `JsonArray` use a `LinkedHashMap` and a `List` and render with `StringBuilder`; `quote` escapes quotes, backslashes and control characters. The reason is simple: the project has exactly one external dependency, the MySQL driver.

### Static files

`StaticFileHandler` reads files from the classpath with `getResourceAsStream`, sets a `Content-Type` by file extension, and writes the bytes to the response. It removes `..` from the path so a request cannot climb out of the `static` folder.

## 6. Security and error handling

Security is enforced on the server, not only in the web page, so a user cannot bypass it by calling the API directly.

| Measure | How the code does it |
| --- | --- |
| Password hashing | `AuthUtil` uses PBKDF2WithHmacSHA256 with 65,536 iterations and a 256-bit key. Plain passwords are never stored |
| Salting | `newSalt()` creates 16 random bytes per user with `SecureRandom`, so two users with the same password get different hashes |
| Constant-time comparison | `constantTimeEquals` XORs every character and checks the total, so response time does not reveal how many characters matched |
| No username leak | `AuthService.login` gives the same message, "Wrong username or password", for an unknown user and a wrong password |
| Input validation | Regular expressions check username (3 to 30 characters), email and roll number in `AuthService`; `VehicleType.fromString` rejects unknown types |
| Role safety on sign-up | `register` accepts only STUDENT or FACULTY, never ADMIN. Admins are created directly in the database |
| Role-based access | `SessionManager.requireLogin` returns 401, `requireAdmin` returns 403, `denyAdmin` stops admins from parking |
| Ownership checks | `scopeOf` passes the user id down; `ParkingService.ownedBy` and `VehicleDAO.findOrCreate` make one user's vehicle invisible and unusable to another |
| SQL injection | `PreparedStatement` with `?` placeholders for all user input |
| Session cookie | Random token, `HttpOnly` (JavaScript cannot read it) and `SameSite=Strict` (not sent on cross-site requests) |
| Path traversal | `StaticFileHandler` strips `..` from the requested path |
| Credentials | `db.properties` is in `.gitignore`; environment variables can override it |

### Error handling strategy

The design uses exception types to carry meaning from the deep layers up to the HTTP reply:

1. A DAO or service throws `IllegalStateException` (a rule was broken) or `IllegalArgumentException` (bad input), or lets `SQLException` pass up.
2. `ApiHandlers.safely` wraps every handler body and maps each type to a status code and a JSON message: `AuthException` to 401 or 403, `IllegalStateException` to 409, `IllegalArgumentException` to 400, `SQLException` to 500.
3. The final `catch (Throwable)` guarantees the browser always receives a reply, even if the MySQL driver is missing, instead of waiting forever.

The handler body is passed as a lambda through the project's own functional interface `HandlerBody { void run() throws Exception; }`, which is what lets one `safely` method wrap all the routes.

## 7. Walkthrough of the key flows

### Park a vehicle (`ParkingService.parkVehicle`)

1. Open a connection and call `setAutoCommit(false)` to start a transaction.
2. `findActiveByVehicleNumber` checks the vehicle is not already parked; if it is, throw `IllegalStateException`.
3. `VehicleDAO.findOrCreate` registers a new vehicle, or confirms an existing one belongs to this user.
4. `ParkingSlotDAO.allocateFreeSlot` picks the first free, in-service slot of the matching type with `FOR UPDATE` and marks it occupied. If none is free, throw "No free slot".
5. `ParkingRecordDAO.insertEntry` inserts a row with `entry_time = NOW()` and `exit_time` NULL.
6. `commit()`. On any exception, `rollback()`.
7. The slot number and location come back to the user, so they know where they parked.

### Unpark and billing (`ParkingService.unparkVehicle`)

1. Inside a transaction, find the active record and check the caller owns the vehicle.
2. Read the slot to get its vehicle type, then read that type's hourly rate from `parking_rates`.
3. `closeRecord` sets `exit_time = NOW()` and `duration_minutes = TIMESTAMPDIFF(MINUTE, entry_time, NOW())`.
4. The fee is computed from the stored duration by the lambda `minutes -> calculateFee(rate, minutes)` and saved in `fee_amount`.
5. `markOccupied(false)` frees the slot, then `commit()`.

The billing rule is every started hour is charged, with a minimum of one hour:

```java
int billedHours = Math.max(1, (int) Math.ceil(durationMinutes / 60.0));
return ratePerHour.multiply(BigDecimal.valueOf(billedHours));
```

| Parked for | Hours billed | Car (20 per hour) | Bike (10 per hour) |
| --- | --- | --- | --- |
| 5 minutes | 1 | 20 | 10 |
| 60 minutes | 1 | 20 | 10 |
| 61 minutes | 2 | 40 | 20 |
| 150 minutes | 3 | 60 | 30 |

### Vehicle finder (`ParkingService.locateVehicle`)

It runs one joined query: the record whose `vehicle_number` matches and whose `exit_time IS NULL`, joined to `vehicles` and `parking_slots` to return the slot number and location. A user searching for someone else's vehicle gets "not found", the same as for an unknown vehicle, so the existence of other people's vehicles is not revealed.

### Occupancy report and dashboard

`ParkingSlotDAO.occupancySummary` groups slots by type with `COUNT(*)` and `SUM(CASE WHEN is_occupied THEN 1 ELSE 0 END)`. `ParkingService.occupancyReport` wraps each row in the nested class `OccupancyStat`, which computes available slots and `occupancyPercent()`. Slots closed for maintenance are excluded so they do not count as capacity. `DashboardDAO` adds breakdowns by zone, revenue for today, 7 days and 30 days, peak hours and overstays, all from SQL aggregate queries.

## 8. Build and run

Requirements: JDK 17 or newer, Maven, and MySQL running locally.

1. **Create the database.** Run `src/main/resources/db/schema.sql` in MySQL Workbench or with `mysql -u root -p < schema.sql`. It creates five tables, 58 slots, the rates and three demo accounts. Re-running it wipes all data.
2. **Optional demo data.** Run `db/demo_data.sql` to load a month of history so the admin charts and 3D map look populated.
3. **Configure the connection.** Copy `db.properties.example` to `src/main/resources/db.properties` and set `db.user` and `db.password`.
4. **Build.** `mvn clean package` produces `target/smart-campus-parking.jar` (the shade plugin bundles the MySQL driver into it).
5. **Run the web app.** `java -jar target/smart-campus-parking.jar`, then open http://localhost:8080.
6. **Or run the console app.** `mvn exec:java -Dexec.mainClass=com.campus.parking.ConsoleApp`.

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `admin123` | ADMIN |
| `student1` | `student123` | STUDENT |
| `faculty1` | `faculty123` | FACULTY |

The Maven `pom.xml` sets Java 17 as source and target, declares `mysql-connector-j` 8.3.0 as the single dependency, uses `maven-shade-plugin` to build one runnable jar whose manifest main class is `com.campus.parking.web.Server`, and `exec-maven-plugin` to run a chosen class directly.

## 9. Viva questions and model answers

### About the project

**Q1. What problem does the project solve?** Campus parking is first-come with no visibility. The system shows free slots, allocates one by vehicle type, records where a vehicle is parked so the owner can find it, bills by duration and gives admins occupancy and revenue reports.

**Q2. Why Java and MySQL?** Java gives strong typing, object orientation and a standard database API (JDBC). MySQL is a free relational database that fits the data: slots, vehicles and sessions are related tables with keys.

**Q3. Why did you not use Spring Boot?** To keep the project pure Java so every part can be explained: the HTTP server, routing, sessions and JSON are all visible code. The cost is more hand-written code. The README lists Spring Boot as a future step if the project grows.

**Q4. Explain the architecture.** Four layers: model (data classes), DAO (SQL for one table each), service (business rules and transactions) and web (HTTP handlers). Each layer calls only the one below. The console app and the web app share the same service layer.

### OOP

**Q5. Which OOP principles did you use and where?** Encapsulation: private fields with getters and setters in `ParkingRecord`. Abstraction: `ParkingService.parkVehicle` hides the SQL. Inheritance: `AuthException extends RuntimeException`. Polymorphism: every route class implements `HttpHandler`, and the server calls `handle` without knowing which one.

**Q6. Why is `VehicleType` an enum?** An enum limits values to CAR, BIKE, BUS and EV, so an invalid type cannot be passed around. It also carries a method, `fromString`, and it maps directly to the MySQL ENUM column.

**Q7. What is a record and where did you use one?** A record (Java 16) is a short syntax for an immutable data class; the compiler generates the constructor, getters, `equals`, `hashCode` and `toString`. `SessionManager.Session` and `DashboardDAO.Occupancy` are records.

**Q8. Why are `DBConnection` and `AuthUtil` `final` with a private constructor?** They only hold static helper methods. `final` stops subclassing and the private constructor stops anyone creating an object, since an instance would be meaningless.

**Q9. What is the difference between an abstract class and an interface? Which did you use?** An interface defines a contract with no state; a class can implement many. An abstract class can hold state and partial code, and a class can extend only one. The project implements the JDK interface `HttpHandler` and defines its own `HandlerBody` interface.

**Q10. What is a functional interface and a lambda?** A functional interface has one abstract method. A lambda is a short way to supply that method. `closeRecord` takes an `IntFunction<BigDecimal>`, and the service passes `minutes -> calculateFee(rate, minutes)`, so the DAO does not need to know the billing rule.

**Q11. Why use `Optional`?** It makes "might not exist" explicit in the return type, so the caller must handle it and cannot hit a `NullPointerException` by accident. `locateVehicle` returns `Optional<ParkingRecord>`.

**Q12. Why `BigDecimal` for money?** `double` cannot represent values like 0.1 exactly and gives rounding errors. `BigDecimal` is exact, and it matches the `DECIMAL` column type in MySQL.

**Q13. Why is `Long durationMinutes` a wrapper and not `long`?** While a vehicle is still parked the duration does not exist yet, so it must be `null`. A primitive cannot be null.

### Exceptions

**Q14. Checked versus unchecked exceptions, with examples from the project?** Checked exceptions must be declared or caught: `SQLException`. Unchecked ones extend `RuntimeException` and need not be declared: `IllegalStateException`, `IllegalArgumentException` and `AuthException`. Business-rule failures are unchecked so the code stays readable, and `safely` turns each into the right HTTP code.

**Q15. What is try-with-resources and why use it?** It closes any `AutoCloseable` (`Connection`, `PreparedStatement`, `ResultSet`) automatically when the block ends, even on an exception, so connections never leak.

**Q16. What does `finally` do in `parkVehicle`?** It runs whether the code succeeded or failed and restores `setAutoCommit(true)`, so the connection is left in a clean state.

### JDBC and SQL

**Q17. What are the steps to use JDBC?** Load the driver, open a `Connection` with `DriverManager`, create a `PreparedStatement`, set the parameters, run `executeQuery` or `executeUpdate`, read the `ResultSet`, and close everything. The project does this in the DAO classes.

**Q18. `Statement` versus `PreparedStatement`?** `PreparedStatement` is pre-compiled, reusable and takes parameters through `?`, which blocks SQL injection. `Statement` runs a raw string. Anything with user input uses `PreparedStatement`.

**Q19. What is SQL injection and how is it prevented here?** It is when user input is pasted into a SQL string and changes the query, for example `' OR '1'='1`. With `?` placeholders the input is only ever data.

**Q20. What is a transaction? Where did you use one?** A group of statements that succeed or fail together (ACID). Parking changes `parking_slots` and `parking_records`, so `parkVehicle` and `unparkVehicle` use `setAutoCommit(false)`, `commit()` and `rollback()`.

**Q21. What if two users park at the same instant?** `allocateFreeSlot` uses `SELECT ... FOR UPDATE`, which locks the chosen row until the transaction ends. The second request waits, then sees that slot occupied and gets the next one.

**Q22. What is a DAO?** Data Access Object: a class that holds all the SQL for one table and hides it from the rest of the program. If the database changed, only the DAOs would change.

**Q23. What are primary and foreign keys in your schema?** Each table has an auto-increment primary key. `vehicles.user_id` references `users`, and `parking_records.vehicle_id` and `slot_id` reference `vehicles` and `parking_slots`. A foreign key also stops deleting a slot that has history, which `ParkingSlotDAO.delete` reports as a friendly error.

**Q24. How does the app know a vehicle is currently parked?** A `parking_records` row with `exit_time IS NULL`.

**Q25. Why is time taken from MySQL (`NOW()`) and not from Java?** Entry and exit then come from the same clock, so the duration cannot be skewed by a difference between the Java machine and the database server.

### Collections, concurrency and strings

**Q26. Why `ConcurrentHashMap` for sessions?** The server runs requests on eight threads. A plain `HashMap` is not thread-safe and could be corrupted by simultaneous writes; `ConcurrentHashMap` is designed for that.

**Q27. What does `synchronized` do in `SchemaMigrator`?** Only one thread at a time can enter the method, so the database upgrade cannot run twice at once.

**Q28. `ArrayList` versus `LinkedHashMap`, and why that map?** `ArrayList` is an ordered, indexed list used for query results. `LinkedHashMap` is a map that remembers insertion order, so JSON fields and report rows always come out in the same order.

**Q29. Why `StringBuilder` in `JsonUtil`?** Strings are immutable, so `+` in a loop creates many temporary objects. `StringBuilder` appends in place.

**Q30. What are the Java 17 features used?** Enums with methods, records, switch expressions with `->`, text blocks in the console menu, `var`, `Optional`, lambdas and `HexFormat`.

### Web and security

**Q31. How does login work end to end?** `/api/login` receives JSON. `AuthService.login` loads the user and hashes the attempt with the stored salt. If it matches, `SessionManager.login` creates a random token, stores the session in memory and sets the `SID` cookie. The browser sends that cookie on every request.

**Q32. How are passwords stored?** As a PBKDF2WithHmacSHA256 hash (65,536 iterations) with a random 16-byte salt per user. The salt defeats precomputed tables and makes equal passwords hash differently. The hash cannot be reversed.

**Q33. What do `HttpOnly` and `SameSite=Strict` do?** `HttpOnly` hides the cookie from JavaScript, limiting theft through cross-site scripting. `SameSite=Strict` stops the browser sending it on requests started from other sites, limiting cross-site request forgery.

**Q34. What do 401, 403, 409 mean in your API?** 401: not logged in. 403: logged in but not allowed (a student calling an admin endpoint). 409: the request conflicts with a rule, such as the vehicle is already parked.

**Q35. What is REST?** An API style where URLs name resources and HTTP methods say the action: GET reads, POST creates or acts, DELETE removes. Data goes as JSON.

### Honest limitations (examiners like these)

**Q36. What are the weaknesses of the project and how would you improve it?** Sessions are in memory, so a restart logs everyone out. Each request opens a new JDBC connection instead of using a connection pool such as HikariCP. JSON is hand-written, so a library like Jackson would be safer at scale. There are no automated tests yet. Reservations, online payment and an admin screen for rates are natural next features.

**Q37. Why no connection pool?** A pool reuses open connections and is faster under load. For a college-scale app a new connection per call is simple and correct, and it is listed as the first scaling improvement.
