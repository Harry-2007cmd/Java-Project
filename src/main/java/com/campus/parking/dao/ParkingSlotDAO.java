package com.campus.parking.dao;

import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-access for the parking_slots table.
 */
public class ParkingSlotDAO {

    public List<ParkingSlot> findAll() throws SQLException {
        String sql = "SELECT * FROM parking_slots ORDER BY slot_number";
        List<ParkingSlot> slots = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                slots.add(map(rs));
            }
        }
        return slots;
    }

    public List<ParkingSlot> findAvailable(VehicleType type) throws SQLException {
        String sql = "SELECT * FROM parking_slots WHERE is_occupied = FALSE AND vehicle_type = ? ORDER BY slot_number";
        List<ParkingSlot> slots = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    slots.add(map(rs));
                }
            }
        }
        return slots;
    }

    /**
     * Finds one free slot for the given vehicle type and atomically marks it
     * occupied, so two simultaneous allocation requests cannot be handed the
     * same slot. Uses SELECT ... FOR UPDATE inside the caller's transaction.
     */
    public Optional<ParkingSlot> allocateFreeSlot(Connection con, VehicleType type) throws SQLException {
        String selectSql = "SELECT * FROM parking_slots WHERE is_occupied = FALSE AND vehicle_type = ? " +
                "ORDER BY slot_number LIMIT 1 FOR UPDATE";
        try (PreparedStatement ps = con.prepareStatement(selectSql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                ParkingSlot slot = map(rs);
                markOccupied(con, slot.getSlotId(), true);
                slot.setOccupied(true);
                return Optional.of(slot);
            }
        }
    }

    public void markOccupied(Connection con, int slotId, boolean occupied) throws SQLException {
        String sql = "UPDATE parking_slots SET is_occupied = ? WHERE slot_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBoolean(1, occupied);
            ps.setInt(2, slotId);
            ps.executeUpdate();
        }
    }

    public Optional<ParkingSlot> findById(int slotId) throws SQLException {
        String sql = "SELECT * FROM parking_slots WHERE slot_id = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, slotId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public ParkingSlot insert(ParkingSlot slot) throws SQLException {
        String sql = "INSERT INTO parking_slots (slot_number, vehicle_type, location, is_occupied) VALUES (?, ?, ?, ?)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, slot.getSlotNumber());
            ps.setString(2, slot.getVehicleType().name());
            ps.setString(3, slot.getLocation());
            ps.setBoolean(4, slot.isOccupied());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    slot.setSlotId(keys.getInt(1));
                }
            }
        }
        return slot;
    }

    /** Count of slots per vehicle type, and how many of those are occupied - used for occupancy reports. */
    public List<Object[]> occupancySummary() throws SQLException {
        String sql = "SELECT vehicle_type, COUNT(*) AS total, " +
                "SUM(CASE WHEN is_occupied THEN 1 ELSE 0 END) AS occupied " +
                "FROM parking_slots GROUP BY vehicle_type ORDER BY vehicle_type";
        List<Object[]> rows = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Object[]{
                        rs.getString("vehicle_type"),
                        rs.getInt("total"),
                        rs.getInt("occupied")
                });
            }
        }
        return rows;
    }

    private ParkingSlot map(ResultSet rs) throws SQLException {
        return new ParkingSlot(
                rs.getInt("slot_id"),
                rs.getString("slot_number"),
                VehicleType.valueOf(rs.getString("vehicle_type")),
                rs.getString("location"),
                rs.getBoolean("is_occupied")
        );
    }
}
