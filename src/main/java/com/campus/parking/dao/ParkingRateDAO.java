package com.campus.parking.dao;

import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data-access for the parking_rates table (hourly price per vehicle type). */
public class ParkingRateDAO {

    public BigDecimal findRate(Connection con, VehicleType type) throws SQLException {
        String sql = "SELECT rate_per_hour FROM parking_rates WHERE vehicle_type = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("No parking rate is configured for " + type);
                }
                return rs.getBigDecimal("rate_per_hour");
            }
        }
    }

    public Map<VehicleType, BigDecimal> findAll() throws SQLException {
        Map<VehicleType, BigDecimal> rates = new LinkedHashMap<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT vehicle_type, rate_per_hour FROM parking_rates ORDER BY vehicle_type")) {
            while (rs.next()) {
                rates.put(VehicleType.valueOf(rs.getString("vehicle_type")), rs.getBigDecimal("rate_per_hour"));
            }
        }
        return rates;
    }
}
