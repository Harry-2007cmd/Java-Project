package com.campus.parking.dao;

import com.campus.parking.util.DBConnection;
import com.campus.parking.util.SchemaMigrator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregate queries behind the admin dashboard. Every date is measured with the
 * database's own clock (CURDATE()/NOW()) so the numbers agree with how entry/exit times were stored.
 * Revenue is attributed to the day the vehicle LEFT (that is when the fee is charged).
 */
public class DashboardDAO {

    /** One row of an occupancy breakdown (per zone or per vehicle type). */
    public record Occupancy(String name, int total, int occupied, int outOfService) {
        /** Slots that can actually be used (out-of-service slots are not capacity). */
        public int usable() { return total - outOfService; }
        public double percent() { return usable() <= 0 ? 0.0 : Math.round(occupied * 1000.0 / usable()) / 10.0; }
    }

    public List<Occupancy> occupancyByZone() throws SQLException {
        return occupancyBy("location");
    }

    public List<Occupancy> occupancyByType() throws SQLException {
        return occupancyBy("vehicle_type");
    }

    // column is one of our two constants above, never user input.
    private List<Occupancy> occupancyBy(String column) throws SQLException {
        SchemaMigrator.ensureSlotColumns();
        String sql = "SELECT " + column + " AS grp, COUNT(*) AS total, " +
                "SUM(CASE WHEN is_occupied THEN 1 ELSE 0 END) AS occupied, " +
                "SUM(CASE WHEN out_of_service THEN 1 ELSE 0 END) AS oos " +
                "FROM parking_slots GROUP BY " + column + " ORDER BY " + column;
        List<Occupancy> rows = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Occupancy(rs.getString("grp"), rs.getInt("total"), rs.getInt("occupied"), rs.getInt("oos")));
            }
        }
        return rows;
    }

    /** Fees collected from sessions that ended in the last {@code days} days (1 = today only). */
    public BigDecimal revenueLastDays(int days) throws SQLException {
        String sql = "SELECT COALESCE(SUM(fee_amount), 0) FROM parking_records " +
                "WHERE exit_time IS NOT NULL AND exit_time >= CURDATE() - INTERVAL " + (days - 1) + " DAY";
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    public int sessionsStartedToday() throws SQLException {
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM parking_records WHERE entry_time >= CURDATE()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Revenue per day for the last {@code days} days, oldest first, with 0 for days that had none. */
    public Map<LocalDate, BigDecimal> dailyRevenue(int days) throws SQLException {
        Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
        try (Connection con = DBConnection.getConnection(); Statement st = con.createStatement()) {
            LocalDate today;
            try (ResultSet rs = st.executeQuery("SELECT CURDATE()")) {
                rs.next();
                today = rs.getDate(1).toLocalDate();
            }
            for (int i = days - 1; i >= 0; i--) {
                out.put(today.minusDays(i), BigDecimal.ZERO);
            }
            String sql = "SELECT DATE(exit_time) AS d, SUM(fee_amount) AS amt FROM parking_records " +
                    "WHERE exit_time IS NOT NULL AND exit_time >= CURDATE() - INTERVAL " + (days - 1) + " DAY " +
                    "GROUP BY DATE(exit_time)";
            try (ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    BigDecimal amt = rs.getBigDecimal("amt");
                    out.computeIfPresent(rs.getDate("d").toLocalDate(), (k, v) -> amt == null ? v : amt);
                }
            }
        }
        return out;
    }

    /** Revenue by vehicle type over the last {@code days} days. */
    public Map<String, BigDecimal> revenueByType(int days) throws SQLException {
        String sql = "SELECT v.vehicle_type AS t, COALESCE(SUM(pr.fee_amount), 0) AS amt " +
                "FROM parking_records pr JOIN vehicles v ON pr.vehicle_id = v.vehicle_id " +
                "WHERE pr.exit_time IS NOT NULL AND pr.exit_time >= CURDATE() - INTERVAL " + (days - 1) + " DAY " +
                "GROUP BY v.vehicle_type ORDER BY v.vehicle_type";
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getString("t"), rs.getBigDecimal("amt"));
            }
        }
        return out;
    }

    /** How many vehicles arrived in each hour of the day (index 0-23) over the last {@code days} days. */
    public int[] arrivalsByHour(int days) throws SQLException {
        int[] counts = new int[24];
        String sql = "SELECT HOUR(entry_time) AS h, COUNT(*) AS c FROM parking_records " +
                "WHERE entry_time >= CURDATE() - INTERVAL " + (days - 1) + " DAY GROUP BY HOUR(entry_time)";
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                counts[rs.getInt("h")] = rs.getInt("c");
            }
        }
        return counts;
    }

    /** Arrivals per weekday over the last {@code days} days; index 0 = Monday ... 6 = Sunday. */
    public int[] arrivalsByWeekday(int days) throws SQLException {
        int[] counts = new int[7];
        String sql = "SELECT DAYOFWEEK(entry_time) AS d, COUNT(*) AS c FROM parking_records " +
                "WHERE entry_time >= CURDATE() - INTERVAL " + (days - 1) + " DAY GROUP BY DAYOFWEEK(entry_time)";
        try (Connection con = DBConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int sqlDay = rs.getInt("d");              // 1 = Sunday ... 7 = Saturday
                counts[(sqlDay + 5) % 7] = rs.getInt("c"); // -> 0 = Monday ... 6 = Sunday
            }
        }
        return counts;
    }
}
