-- ============================================================
-- Smart Campus Parking and Vehicle Finder - Database Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS campus_parking;
USE campus_parking;

-- Drop existing tables (useful while re-running during development).
-- NOTE: re-running this script wipes ALL data, including registered
-- users - that's intentional for a dev/demo reset, not meant for production.
DROP TABLE IF EXISTS parking_records;
DROP TABLE IF EXISTS vehicles;
DROP TABLE IF EXISTS parking_slots;
DROP TABLE IF EXISTS parking_rates;
DROP TABLE IF EXISTS users;

-- ------------------------------------------------------------
-- users: student, faculty and admin accounts
-- email is collected at sign-up; roll_no is for students only (NULL for
-- faculty/admins). UNIQUE allows many NULLs, so accounts without them are fine.
-- ------------------------------------------------------------
CREATE TABLE users (
    user_id        INT AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(50)  NOT NULL UNIQUE,
    password_hash  VARCHAR(64)  NOT NULL,   -- PBKDF2WithHmacSHA256, hex-encoded
    salt           VARCHAR(32)  NOT NULL,   -- hex-encoded random salt, unique per user
    full_name      VARCHAR(100) NOT NULL,
    role           ENUM('STUDENT', 'ADMIN', 'FACULTY') NOT NULL DEFAULT 'STUDENT',
    email          VARCHAR(100) NULL UNIQUE,
    roll_no        VARCHAR(30)  NULL UNIQUE
);

-- ------------------------------------------------------------
-- parking_slots: every physical slot on campus
-- ------------------------------------------------------------
CREATE TABLE parking_slots (
    slot_id        INT AUTO_INCREMENT PRIMARY KEY,
    slot_number    VARCHAR(20)  NOT NULL UNIQUE,
    vehicle_type   ENUM('CAR', 'BIKE', 'BUS', 'EV') NOT NULL,
    location       VARCHAR(100) NOT NULL,          -- e.g. "Block A - Ground Floor"
    is_occupied    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- ------------------------------------------------------------
-- parking_rates: billing rate per vehicle type, charged per hour
-- (part charged up to the next full hour - see ParkingService)
-- ------------------------------------------------------------
CREATE TABLE parking_rates (
    vehicle_type   ENUM('CAR', 'BIKE', 'BUS', 'EV') PRIMARY KEY,
    rate_per_hour  DECIMAL(6,2) NOT NULL
);

-- ------------------------------------------------------------
-- vehicles: registry of vehicles known to the system
-- ------------------------------------------------------------
CREATE TABLE vehicles (
    vehicle_id     INT AUTO_INCREMENT PRIMARY KEY,
    vehicle_number VARCHAR(20)  NOT NULL UNIQUE,    -- license plate
    vehicle_type   ENUM('CAR', 'BIKE', 'BUS', 'EV') NOT NULL,
    owner_name     VARCHAR(100) NOT NULL,
    owner_contact  VARCHAR(20),
    user_id        INT NULL,                        -- the student account that registered this vehicle, if any
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- ------------------------------------------------------------
-- parking_records: one row per parking session (park -> unpark)
-- ------------------------------------------------------------
CREATE TABLE parking_records (
    record_id      INT AUTO_INCREMENT PRIMARY KEY,
    vehicle_id     INT NOT NULL,
    slot_id        INT NOT NULL,
    entry_time     DATETIME NOT NULL,
    exit_time      DATETIME NULL,                   -- NULL while vehicle is still parked
    duration_minutes INT NULL,                       -- filled in when vehicle exits
    fee_amount     DECIMAL(8,2) NULL,                -- filled in when vehicle exits, based on parking_rates
    FOREIGN KEY (vehicle_id) REFERENCES vehicles(vehicle_id),
    FOREIGN KEY (slot_id) REFERENCES parking_slots(slot_id)
);

-- ------------------------------------------------------------
-- Sample seed data so the app has something to show immediately
-- ------------------------------------------------------------
INSERT INTO parking_slots (slot_number, vehicle_type, location, is_occupied) VALUES
('A-01', 'CAR',  'Block A - Ground Floor', FALSE),
('A-02', 'CAR',  'Block A - Ground Floor', FALSE),
('A-03', 'CAR',  'Block A - Ground Floor', FALSE),
('B-01', 'BIKE', 'Block B - Two Wheeler Shed', FALSE),
('B-02', 'BIKE', 'Block B - Two Wheeler Shed', FALSE),
('B-03', 'BIKE', 'Block B - Two Wheeler Shed', FALSE),
('C-01', 'BUS',  'Block C - Bus Bay', FALSE),
('C-02', 'BUS',  'Block C - Bus Bay', FALSE),
('D-01', 'EV',   'Block D - EV Charging Zone', FALSE),
('D-02', 'EV',   'Block D - EV Charging Zone', FALSE);

-- Billing rates (rupees per hour, charged for the started hour)
INSERT INTO parking_rates (vehicle_type, rate_per_hour) VALUES
('CAR',  20.00),
('BIKE', 10.00),
('BUS',  50.00),
('EV',   15.00);

-- Demo accounts so you can log in immediately:
--   admin    / admin123   (role ADMIN)
--   student1 / student123 (role STUDENT)
--   faculty1 / faculty123 (role FACULTY)
-- (password_hash/salt below are real PBKDF2WithHmacSHA256 values generated
-- with com.campus.parking.util.AuthUtil - not placeholders)
INSERT INTO users (username, password_hash, salt, full_name, role, email, roll_no) VALUES
('admin',    'c3462c9c28de8bf6a933cf5d29eaf174deb7ec07dcd114b953e579c2370c60ff', 'e7d2c9b0eda668e480a03d19fe8d7168', 'Campus Admin', 'ADMIN',   NULL, NULL),
('student1', 'eee4ecc95841f8740e7cc1a2ab26a788e1c39ba13275dc8967d28d164d8fa02e', '4f5bf1186acdcdacc11417ba28dab9f1', 'Demo Student', 'STUDENT', 'student1@campus.edu', 'CS2024001'),
('faculty1', 'e65a42002cb6ec9144db72937771d204623acac9f25502561e1e3147f8a72517', 'c2c77080be8cfa697818572295498730', 'Demo Faculty', 'FACULTY', 'faculty1@campus.edu', NULL);
