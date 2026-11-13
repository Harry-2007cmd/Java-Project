-- ============================================================
-- Smart Campus Parking and Vehicle Finder - Database Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS campus_parking;
USE campus_parking;

-- Drop existing tables (useful while re-running during development)
DROP TABLE IF EXISTS parking_records;
DROP TABLE IF EXISTS vehicles;
DROP TABLE IF EXISTS parking_slots;

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
-- vehicles: registry of vehicles known to the system
-- ------------------------------------------------------------
CREATE TABLE vehicles (
    vehicle_id     INT AUTO_INCREMENT PRIMARY KEY,
    vehicle_number VARCHAR(20)  NOT NULL UNIQUE,    -- license plate
    vehicle_type   ENUM('CAR', 'BIKE', 'BUS', 'EV') NOT NULL,
    owner_name     VARCHAR(100) NOT NULL,
    owner_contact  VARCHAR(20)
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
    duration_minutes INT NULL,                      -- filled in when vehicle exits
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
