package com.campus.parking.dao;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-access for the parking_records table - the log of park/unpark events
 * that the "vehicle finder" and occupancy reports are built from.
 */
public class ParkingRecordDAO {

    public ParkingRecord insertEntry(Connection con, int vehicleId, int slotId) throws SQLException {
        String sql = "INSERT INTO parking_records (vehicle_id, slot_id, entry_time) VALUES (?, ?, NOW())";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, vehicleId);
            ps.setInt(2, slotId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return findById(con, keys.getInt(1)).orElseThrow();
            }
        }
    }

    public Optional<ParkingRecord> findById(Connection con, int recordId) throws SQLException {
        String sql = "SELECT * FROM parking_records WHERE record_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, recordId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBasic(rs)) : Optional.empty();
            }
        }
    }

    /** The currently-active (not yet exited) record for a vehicle, if it is parked somewhere - this IS the vehicle finder. */
    public Optional<ParkingRecord> findActiveByVehicleNumber(String vehicleNumber) throws SQLException {
        String sql = "SELECT pr.*, v.vehicle_number, s.slot_number, s.location " +
                "FROM parking_records pr " +
                "JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
                "JOIN parking_slots s ON pr.slot_id = s.slot_id " +
                "WHERE v.vehicle_number = ? AND pr.exit_time IS NULL " +
                "ORDER BY pr.entry_time DESC LIMIT 1";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, vehicleNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapJoined(rs)) : Optional.empty();
            }
        }
    }

    public Optional<ParkingRecord> findActiveByVehicleNumber(Connection con, String vehicleNumber) throws SQLException {
        String sql = "SELECT pr.*, v.vehicle_number, s.slot_number, s.location " +
                "FROM parking_records pr " +
                "JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
                "JOIN parking_slots s ON pr.slot_id = s.slot_id " +
                "WHERE v.vehicle_number = ? AND pr.exit_time IS NULL " +
                "ORDER BY pr.entry_time DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, vehicleNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapJoined(rs)) : Optional.empty();
            }
        }
    }

    public List<ParkingRecord> findAllActive() throws SQLException {
        String sql = "SELECT pr.*, v.vehicle_number, s.slot_number, s.location " +
                "FROM parking_records pr " +
                "JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
                "JOIN parking_slots s ON pr.slot_id = s.slot_id " +
                "WHERE pr.exit_time IS NULL ORDER BY pr.entry_time";
        List<ParkingRecord> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapJoined(rs));
            }
        }
        return list;
    }

    /** Marks a record as exited "now", calculates and stores the duration, and returns the updated record. */
    public ParkingRecord closeRecord(Connection con, int recordId) throws SQLException {
        String sql = "UPDATE parking_records SET exit_time = NOW(), " +
                "duration_minutes = TIMESTAMPDIFF(MINUTE, entry_time, NOW()) " +
                "WHERE record_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, recordId);
            ps.executeUpdate();
        }
        return findById(con, recordId).orElseThrow();
    }

    public List<ParkingRecord> findHistory(int limit) throws SQLException {
        String sql = "SELECT pr.*, v.vehicle_number, s.slot_number, s.location " +
                "FROM parking_records pr " +
                "JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
                "JOIN parking_slots s ON pr.slot_id = s.slot_id " +
                "ORDER BY pr.entry_time DESC LIMIT ?";
        List<ParkingRecord> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapJoined(rs));
                }
            }
        }
        return list;
    }

    private ParkingRecord mapBasic(ResultSet rs) throws SQLException {
        ParkingRecord r = new ParkingRecord();
        r.setRecordId(rs.getInt("record_id"));
        r.setVehicleId(rs.getInt("vehicle_id"));
        r.setSlotId(rs.getInt("slot_id"));
        r.setEntryTime(toLocalDateTime(rs.getTimestamp("entry_time")));
        r.setExitTime(toLocalDateTime(rs.getTimestamp("exit_time")));
        long duration = rs.getLong("duration_minutes");
        r.setDurationMinutes(rs.wasNull() ? null : duration);
        return r;
    }

    private ParkingRecord mapJoined(ResultSet rs) throws SQLException {
        ParkingRecord r = mapBasic(rs);
        r.setVehicleNumber(rs.getString("vehicle_number"));
        r.setSlotNumber(rs.getString("slot_number"));
        r.setLocation(rs.getString("location"));
        return r;
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
