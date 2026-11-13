package com.campus.parking.dao;

import com.campus.parking.model.Vehicle;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Data-access for the vehicles table.
 */
public class VehicleDAO {

    public Optional<Vehicle> findByNumber(String vehicleNumber) throws SQLException {
        String sql = "SELECT * FROM vehicles WHERE vehicle_number = ?";
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, vehicleNumber);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
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

    /** Inserts the vehicle if it is new, otherwise returns the existing record (vehicle number is unique). */
    public Vehicle findOrCreate(Connection con, String vehicleNumber, VehicleType type, String ownerName, String ownerContact) throws SQLException {
        Optional<Vehicle> existing = findByNumber(con, vehicleNumber);
        if (existing.isPresent()) {
            return existing.get();
        }
        String sql = "INSERT INTO vehicles (vehicle_number, vehicle_type, owner_name, owner_contact) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, vehicleNumber);
            ps.setString(2, type.name());
            ps.setString(3, ownerName);
            ps.setString(4, ownerContact);
            ps.executeUpdate();
            Vehicle v = new Vehicle(0, vehicleNumber, type, ownerName, ownerContact);
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    v.setVehicleId(keys.getInt(1));
                }
            }
            return v;
        }
    }

    private Vehicle map(ResultSet rs) throws SQLException {
        return new Vehicle(
                rs.getInt("vehicle_id"),
                rs.getString("vehicle_number"),
                VehicleType.valueOf(rs.getString("vehicle_type")),
                rs.getString("owner_name"),
                rs.getString("owner_contact")
        );
    }
}
