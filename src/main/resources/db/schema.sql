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
    is_occupied    BOOLEAN      NOT NULL DEFAULT FALSE,
    out_of_service BOOLEAN      NOT NULL DEFAULT FALSE   -- admin has closed the slot for maintenance
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
-- Campus layout: zone names follow the parking areas Chitkara University lists publicly
-- (Turing Block, Gate 3, rear of Picasso Block, two-wheeler parking, bus bay, EV zone).
-- Slot counts are illustrative - edit them to match your campus.
INSERT INTO parking_slots (slot_number, vehicle_type, location, is_occupied) VALUES
('T-01', 'CAR', 'Turing Block Parking', FALSE),
('T-02', 'CAR', 'Turing Block Parking', FALSE),
('T-03', 'CAR', 'Turing Block Parking', FALSE),
('T-04', 'CAR', 'Turing Block Parking', FALSE),
('T-05', 'CAR', 'Turing Block Parking', FALSE),
('T-06', 'CAR', 'Turing Block Parking', FALSE),
('T-07', 'CAR', 'Turing Block Parking', FALSE),
('T-08', 'CAR', 'Turing Block Parking', FALSE),
('T-09', 'CAR', 'Turing Block Parking', FALSE),
('T-10', 'CAR', 'Turing Block Parking', FALSE),
('T-11', 'CAR', 'Turing Block Parking', FALSE),
('T-12', 'CAR', 'Turing Block Parking', FALSE),
('G-01', 'CAR', 'Gate 3 Parking', FALSE),
('G-02', 'CAR', 'Gate 3 Parking', FALSE),
('G-03', 'CAR', 'Gate 3 Parking', FALSE),
('G-04', 'CAR', 'Gate 3 Parking', FALSE),
('G-05', 'CAR', 'Gate 3 Parking', FALSE),
('G-06', 'CAR', 'Gate 3 Parking', FALSE),
('G-07', 'CAR', 'Gate 3 Parking', FALSE),
('G-08', 'CAR', 'Gate 3 Parking', FALSE),
('G-09', 'CAR', 'Gate 3 Parking', FALSE),
('G-10', 'CAR', 'Gate 3 Parking', FALSE),
('P-01', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-02', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-03', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-04', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-05', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-06', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-07', 'CAR', 'Picasso Block (Rear)', FALSE),
('P-08', 'CAR', 'Picasso Block (Rear)', FALSE),
('W-01', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-02', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-03', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-04', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-05', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-06', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-07', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-08', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-09', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-10', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-11', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-12', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-13', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-14', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-15', 'BIKE', 'Two-Wheeler Parking', FALSE),
('W-16', 'BIKE', 'Two-Wheeler Parking', FALSE),
('S-01', 'BUS', 'Bus Bay', FALSE),
('S-02', 'BUS', 'Bus Bay', FALSE),
('S-03', 'BUS', 'Bus Bay', FALSE),
('S-04', 'BUS', 'Bus Bay', FALSE),
('S-05', 'BUS', 'Bus Bay', FALSE),
('S-06', 'BUS', 'Bus Bay', FALSE),
('E-01', 'EV', 'EV Charging Zone', FALSE),
('E-02', 'EV', 'EV Charging Zone', FALSE),
('E-03', 'EV', 'EV Charging Zone', FALSE),
('E-04', 'EV', 'EV Charging Zone', FALSE),
('E-05', 'EV', 'EV Charging Zone', FALSE),
('E-06', 'EV', 'EV Charging Zone', FALSE);

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
