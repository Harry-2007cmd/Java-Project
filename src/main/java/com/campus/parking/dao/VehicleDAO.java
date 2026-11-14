package com.campus.parking.dao;

import com.campus.parking.model.Vehicle;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Optional;

/**
 * Data-access for the vehicles table.
 */
public class VehicleDAO {

    public Optional<Vehicle> findByNumber(String vehicleNumber) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            return findByNumber(con, vehicleNumber);
        }
    }

    public Optional<Vehicle> findByNumber(Connection con, String vehicleNumber) throws SQLException {
        String sql = "SELECT * FROM vehicles WHERE vehicle_number = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, vehicleNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Returns the existing vehicle, or registers a new one (vehicle number is unique).
     *
     * userId is the logged-in student doing the parking, or null for an admin:
     *  - a new vehicle is registered to that student;
     *  - an existing vehicle nobody has claimed yet becomes theirs;
     *  - an existing vehicle registered to a DIFFERENT student is refused, so one
     *    student cannot park (and later see or unpark) another student's vehicle.
     */
    public Vehicle findOrCreate(Connection con, String vehicleNumber, VehicleType type,
                                String ownerName, String ownerContact, Integer userId) throws SQLException {
        Optional<Vehicle> existing = findByNumber(con, vehicleNumber);
        if (existing.isPresent()) {
            Vehicle v = existing.get();
            if (userId != null) {
                if (v.getUserId() != null && !v.getUserId().equals(userId)) {
                    throw new IllegalStateException("Vehicle " + vehicleNumber + " is registered to another account");
                }
                if (v.getUserId() == null) {
                    try (PreparedStatement ps = con.prepareStatement("UPDATE vehicles SET user_id = ? WHERE vehicle_id = ?")) {
                        ps.setInt(1, userId);
                        ps.setInt(2, v.getVehicleId());
                        ps.executeUpdate();
                    }
                    v.setUserId(userId);
                }
            }
            return v;
        }

        String sql = "INSERT INTO vehicles (vehicle_number, vehicle_type, owner_name, owner_contact, user_id) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, vehicleNumber);
            ps.setString(2, type.name());
            ps.setString(3, ownerName);
            ps.setString(4, ownerContact);
            if (userId != null) {
                ps.setInt(5, userId);
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.executeUpdate();
            Vehicle v = new Vehicle(0, vehicleNumber, type, ownerName, ownerContact);
            v.setUserId(userId);
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    v.setVehicleId(keys.getInt(1));
                }
            }
            return v;
        }
    }

    private Vehicle map(ResultSet rs) throws SQLException {
        Vehicle v = new Vehicle(
                rs.getInt("vehicle_id"),
                rs.getString("vehicle_number"),
                VehicleType.valueOf(rs.getString("vehicle_type")),
                rs.getString("owner_name"),
                rs.getString("owner_contact")
        );
        int uid = rs.getInt("user_id");
        v.setUserId(rs.wasNull() ? null : uid);
        return v;
    }
}
