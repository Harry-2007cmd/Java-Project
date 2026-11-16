package com.campus.parking.dao;

import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;
import com.campus.parking.util.SchemaMigrator;

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
        SchemaMigrator.ensureSlotColumns();
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
        String sql = "SELECT * FROM parking_slots WHERE is_occupied = FALSE AND out_of_service = FALSE AND vehicle_type = ? ORDER BY slot_number";
        SchemaMigrator.ensureSlotColumns();
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
        SchemaMigrator.ensureSlotColumns();
        String selectSql = "SELECT * FROM parking_slots WHERE is_occupied = FALSE AND out_of_service = FALSE AND vehicle_type = ? " +
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
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            throw new IllegalStateException("Slot number " + slot.getSlotNumber() + " already exists");
        }
        return slot;
    }

    /**
     * Deletes a slot. Refused while the slot is occupied, and for slots that already
     * appear in the parking history (the history rows still point at them).
     */
    public void delete(int slotId) throws SQLException {
        ParkingSlot slot = findById(slotId).orElseThrow(() -> new IllegalStateException("Slot not found"));
        if (slot.isOccupied()) {
            throw new IllegalStateException("Slot " + slot.getSlotNumber() + " is occupied right now and cannot be deleted");
        }
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM parking_slots WHERE slot_id = ?")) {
            ps.setInt(1, slotId);
            ps.executeUpdate();
        } catch (java.sql.SQLIntegrityConstraintViolationException e) {
            throw new IllegalStateException("Slot " + slot.getSlotNumber() + " has parking history and cannot be deleted");
        }
    }

    /** Count of slots per vehicle type, and how many of those are occupied - used for occupancy reports. */
    public List<Object[]> occupancySummary() throws SQLException {
        String sql = "SELECT vehicle_type, COUNT(*) AS total, " +
                "SUM(CASE WHEN is_occupied THEN 1 ELSE 0 END) AS occupied " +
                "FROM parking_slots WHERE out_of_service = FALSE GROUP BY vehicle_type ORDER BY vehicle_type";
        SchemaMigrator.ensureSlotColumns();
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

    /**
     * Takes a slot out of service (maintenance) or puts it back. An occupied slot cannot be taken
     * out of service - the vehicle has to leave first - so a car is never parked on a "closed" slot.
     */
    public ParkingSlot setOutOfService(int slotId, boolean outOfService) throws SQLException {
        SchemaMigrator.ensureSlotColumns();
        ParkingSlot slot = findById(slotId).orElseThrow(() -> new IllegalStateException("Slot not found"));
        if (outOfService && slot.isOccupied()) {
            throw new IllegalStateException("Slot " + slot.getSlotNumber() + " is occupied right now - wait for the vehicle to leave before closing it");
        }
        String sql = "UPDATE parking_slots SET out_of_service = ? WHERE slot_id = ? AND (? = FALSE OR is_occupied = FALSE)";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBoolean(1, outOfService);
            ps.setInt(2, slotId);
            ps.setBoolean(3, outOfService);
            if (ps.executeUpdate() == 0 && outOfService) {
                throw new IllegalStateException("Slot " + slot.getSlotNumber() + " was just taken - try again when it is free");
            }
        }
        slot.setOutOfService(outOfService);
        return slot;
    }

    private ParkingSlot map(ResultSet rs) throws SQLException {
        ParkingSlot slot = map0(rs);
        try {
            slot.setOutOfService(rs.getBoolean("out_of_service"));
        } catch (SQLException ignored) {
            // un-migrated database: treat every slot as in service
        }
        return slot;
    }

    private ParkingSlot map0(ResultSet rs) throws SQLException {
        return new ParkingSlot(
                rs.getInt("slot_id"),
                rs.getString("slot_number"),
                VehicleType.valueOf(rs.getString("vehicle_type")),
                rs.getString("location"),
                rs.getBoolean("is_occupied")
        );
    }
}
