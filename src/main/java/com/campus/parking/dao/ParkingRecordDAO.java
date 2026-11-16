package com.campus.parking.dao;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.util.DBConnection;

import java.math.BigDecimal;
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
 * that the "vehicle finder", billing and occupancy reports are built from.
 */
public class ParkingRecordDAO {

    // Shared by every query that needs vehicle/slot details alongside the record.
    private static final String JOINED_SELECT =
            "SELECT pr.*, v.vehicle_number, v.user_id AS owner_user_id, v.owner_name, v.vehicle_type AS vehicle_type_name, " +
            "TIMESTAMPDIFF(MINUTE, pr.entry_time, NOW()) AS live_minutes, s.slot_number, s.location " +
            "FROM parking_records pr " +
            "JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
            "JOIN parking_slots s ON pr.slot_id = s.slot_id ";

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
        try (Connection con = DBConnection.getConnection()) {
            return findActiveByVehicleNumber(con, vehicleNumber);
        }
    }

    public Optional<ParkingRecord> findActiveByVehicleNumber(Connection con, String vehicleNumber) throws SQLException {
        String sql = JOINED_SELECT +
                "WHERE v.vehicle_number = ? AND pr.exit_time IS NULL " +
                "ORDER BY pr.entry_time DESC LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, vehicleNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapJoined(rs)) : Optional.empty();
            }
        }
    }

    /** Everything currently parked. If userId is not null, only that student's vehicles. */
    public List<ParkingRecord> findAllActive(Integer userId) throws SQLException {
        String sql = JOINED_SELECT +
                "WHERE pr.exit_time IS NULL " + (userId != null ? "AND v.user_id = ? " : "") +
                "ORDER BY pr.entry_time";
        List<ParkingRecord> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            if (userId != null) ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapJoined(rs));
                }
            }
        }
        return list;
    }

    /**
     * Marks a record as exited "now", stores the duration and the fee, and returns the
     * updated record. Duration is measured by MySQL itself so entry and exit use the same clock.
     */
    public ParkingRecord closeRecord(Connection con, int recordId, java.util.function.IntFunction<BigDecimal> feeForMinutes) throws SQLException {
        // Close first so exit_time/duration_minutes are filled in by MySQL, then read the
        // duration back to work out the fee from it.
        String closeSql = "UPDATE parking_records SET exit_time = NOW(), " +
                "duration_minutes = TIMESTAMPDIFF(MINUTE, entry_time, NOW()) " +
                "WHERE record_id = ?";
        try (PreparedStatement ps = con.prepareStatement(closeSql)) {
            ps.setInt(1, recordId);
            ps.executeUpdate();
        }
        ParkingRecord closed = findById(con, recordId).orElseThrow();
        BigDecimal fee = feeForMinutes.apply(closed.getDurationMinutes().intValue());
        try (PreparedStatement ps = con.prepareStatement("UPDATE parking_records SET fee_amount = ? WHERE record_id = ?")) {
            ps.setBigDecimal(1, fee);
            ps.setInt(2, recordId);
            ps.executeUpdate();
        }
        closed.setFeeAmount(fee);
        return closed;
    }

    /** Most recent sessions first. If userId is not null, only that student's vehicles. */
    public List<ParkingRecord> findHistory(int limit, Integer userId) throws SQLException {
        String sql = JOINED_SELECT +
                (userId != null ? "WHERE v.user_id = ? " : "") +
                "ORDER BY pr.entry_time DESC LIMIT ?";
        List<ParkingRecord> list = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            int i = 1;
            if (userId != null) ps.setInt(i++, userId);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapJoined(rs));
                }
            }
        }
        return list;
    }

    /** Total fees collected so far (finished sessions only) - shown on the admin report. */
    public BigDecimal totalRevenue() throws SQLException {
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(SUM(fee_amount), 0) FROM parking_records")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
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
        r.setFeeAmount(rs.getBigDecimal("fee_amount"));
        return r;
    }

    private ParkingRecord mapJoined(ResultSet rs) throws SQLException {
        ParkingRecord r = mapBasic(rs);
        r.setVehicleNumber(rs.getString("vehicle_number"));
        r.setSlotNumber(rs.getString("slot_number"));
        r.setLocation(rs.getString("location"));
        r.setOwnerName(rs.getString("owner_name"));
        r.setVehicleType(rs.getString("vehicle_type_name"));
        r.setLiveMinutes(rs.getLong("live_minutes"));
        int owner = rs.getInt("owner_user_id");
        r.setOwnerUserId(rs.wasNull() ? null : owner);
        return r;
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
