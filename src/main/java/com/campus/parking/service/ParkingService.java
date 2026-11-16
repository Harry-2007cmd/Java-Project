package com.campus.parking.service;

import com.campus.parking.dao.ParkingRateDAO;
import com.campus.parking.dao.ParkingRecordDAO;
import com.campus.parking.dao.ParkingSlotDAO;
import com.campus.parking.dao.VehicleDAO;
import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * All the business rules live here, on top of the plain DAOs:
 *   - allocating a free slot that matches the vehicle's type
 *   - parking / un-parking a vehicle (and keeping parking_slots.is_occupied in sync)
 *   - billing: the fee charged when a vehicle leaves
 *   - finding where a given vehicle currently is
 *   - building the occupancy report
 *   - admin slot management
 *
 * "userId" parameters work like this: a student's id restricts the call to that
 * student's own vehicles; null means "no restriction" (used for admins).
 *
 * Methods that touch more than one table open a single JDBC transaction so
 * the slot's occupied flag and the parking_records row never drift apart.
 */
public class ParkingService {

    private final ParkingSlotDAO slotDAO = new ParkingSlotDAO();
    private final VehicleDAO vehicleDAO = new VehicleDAO();
    private final ParkingRecordDAO recordDAO = new ParkingRecordDAO();
    private final ParkingRateDAO rateDAO = new ParkingRateDAO();

    public List<ParkingSlot> listAllSlots() throws SQLException {
        return slotDAO.findAll();
    }

    public List<ParkingSlot> listAvailableSlots(VehicleType type) throws SQLException {
        return slotDAO.findAvailable(type);
    }

    /**
     * Parks a vehicle: registers it if it's new, allocates the first free slot
     * matching its vehicle type, marks that slot occupied, and opens a parking
     * record. Returns the record (with slot/location info filled in) so the
     * caller can show the student where they parked.
     *
     * Throws IllegalStateException if no slot of that type is free, if the
     * vehicle is already parked, or if it belongs to a different student account.
     */
    public ParkingRecord parkVehicle(String vehicleNumber, VehicleType type, String ownerName,
                                     String ownerContact, Integer userId) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                Optional<ParkingRecord> already = recordDAO.findActiveByVehicleNumber(con, vehicleNumber);
                if (already.isPresent()) {
                    throw new IllegalStateException("Vehicle " + vehicleNumber +
                            " is already parked at slot " + already.get().getSlotNumber());
                }

                // Check ownership before taking a slot, so a refusal leaves nothing half-done.
                var vehicle = vehicleDAO.findOrCreate(con, vehicleNumber, type, ownerName, ownerContact, userId);

                Optional<ParkingSlot> freeSlot = slotDAO.allocateFreeSlot(con, type);
                if (freeSlot.isEmpty()) {
                    throw new IllegalStateException("No free " + type + " slot is available right now");
                }

                ParkingRecord record = recordDAO.insertEntry(con, vehicle.getVehicleId(), freeSlot.get().getSlotId());
                record.setVehicleNumber(vehicleNumber);
                record.setSlotNumber(freeSlot.get().getSlotNumber());
                record.setLocation(freeSlot.get().getLocation());

                con.commit();
                return record;
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /**
     * Un-parks a vehicle: closes its active parking record (setting exit time,
     * duration and fee) and frees up its slot again. A student (userId not null)
     * can only un-park their own vehicle.
     */
    public ParkingRecord unparkVehicle(String vehicleNumber, Integer userId) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                ParkingRecord active = recordDAO.findActiveByVehicleNumber(con, vehicleNumber)
                        .filter(r -> ownedBy(r, userId))
                        .orElseThrow(() -> new IllegalStateException("Vehicle " + vehicleNumber + " is not currently parked"));

                // The slot's type is the vehicle's type (that is how it was allocated), so use it for the rate.
                ParkingSlot slot = slotDAO.findById(active.getSlotId()).orElseThrow();
                BigDecimal rate = rateDAO.findRate(con, slot.getVehicleType());

                ParkingRecord closed = recordDAO.closeRecord(con, active.getRecordId(), minutes -> calculateFee(rate, minutes));
                slotDAO.markOccupied(con, active.getSlotId(), false);

                closed.setVehicleNumber(vehicleNumber);
                closed.setSlotNumber(active.getSlotNumber());
                closed.setLocation(active.getLocation());

                con.commit();
                return closed;
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /**
     * Billing rule: the hourly rate is charged for every started hour, with a
     * minimum of one hour. e.g. at Rs.20/hour: 5 min -> 20, 60 min -> 20, 61 min -> 40.
     */
    public static BigDecimal calculateFee(BigDecimal ratePerHour, int durationMinutes) {
        int billedHours = Math.max(1, (int) Math.ceil(durationMinutes / 60.0));
        return ratePerHour.multiply(BigDecimal.valueOf(billedHours));
    }

    /** The "vehicle finder": where is this vehicle parked right now, if at all? */
    public Optional<ParkingRecord> locateVehicle(String vehicleNumber, Integer userId) throws SQLException {
        // A student searching for someone else's vehicle gets "not found", same as for an unknown vehicle.
        return recordDAO.findActiveByVehicleNumber(vehicleNumber).filter(r -> ownedBy(r, userId));
    }

    public List<ParkingRecord> listCurrentlyParked(Integer userId) throws SQLException {
        return recordDAO.findAllActive(userId);
    }

    public List<ParkingRecord> listHistory(int limit, Integer userId) throws SQLException {
        return recordDAO.findHistory(limit, userId);
    }

    /** Hourly rate per vehicle type, for display. */
    public Map<VehicleType, BigDecimal> listRates() throws SQLException {
        return rateDAO.findAll();
    }

    /** Total fees collected from finished sessions (admin report). */
    public BigDecimal totalRevenue() throws SQLException {
        return recordDAO.totalRevenue();
    }

    // ---------------- admin: slot management ----------------

    public ParkingSlot addSlot(String slotNumber, VehicleType type, String location) throws SQLException {
        if (slotNumber == null || slotNumber.isBlank()) {
            throw new IllegalArgumentException("Slot number is required");
        }
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Location is required");
        }
        return slotDAO.insert(new ParkingSlot(0, slotNumber.trim().toUpperCase(), type, location.trim(), false));
    }

    public void deleteSlot(int slotId) throws SQLException {
        slotDAO.delete(slotId);
    }

    /** Admin: close a free slot for maintenance (true) or reopen it (false). */
    public ParkingSlot setSlotMaintenance(int slotId, boolean outOfService) throws SQLException {
        return slotDAO.setOutOfService(slotId, outOfService);
    }

    // ---------------- reports ----------------

    /**
     * Occupancy report: per vehicle type, how many slots total, how many are
     * occupied right now, and the occupancy percentage.
     */
    public Map<VehicleType, OccupancyStat> occupancyReport() throws SQLException {
        Map<VehicleType, OccupancyStat> report = new LinkedHashMap<>();
        for (Object[] row : slotDAO.occupancySummary()) {
            VehicleType type = VehicleType.valueOf((String) row[0]);
            int total = (Integer) row[1];
            int occupied = (Integer) row[2];
            report.put(type, new OccupancyStat(total, occupied));
        }
        return report;
    }

    /** True if no restriction applies (userId null) or the record's vehicle belongs to that student. */
    private static boolean ownedBy(ParkingRecord record, Integer userId) {
        return userId == null || userId.equals(record.getOwnerUserId());
    }

    /** Small value object describing one row of the occupancy report. */
    public static class OccupancyStat {
        public final int totalSlots;
        public final int occupiedSlots;

        public OccupancyStat(int totalSlots, int occupiedSlots) {
            this.totalSlots = totalSlots;
            this.occupiedSlots = occupiedSlots;
        }

        public int availableSlots() {
            return totalSlots - occupiedSlots;
        }

        public double occupancyPercent() {
            return totalSlots == 0 ? 0.0 : (occupiedSlots * 100.0) / totalSlots;
        }
    }
}
