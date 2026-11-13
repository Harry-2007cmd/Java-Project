package com.campus.parking.service;

import com.campus.parking.dao.ParkingRecordDAO;
import com.campus.parking.dao.ParkingSlotDAO;
import com.campus.parking.dao.VehicleDAO;
import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.VehicleType;
import com.campus.parking.util.DBConnection;

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
 *   - calculating how long a vehicle has been / was parked
 *   - finding where a given vehicle currently is
 *   - building the occupancy report
 *
 * Methods that touch more than one table open a single JDBC transaction so
 * the slot's occupied flag and the parking_records row never drift apart.
 */
public class ParkingService {

    private final ParkingSlotDAO slotDAO = new ParkingSlotDAO();
    private final VehicleDAO vehicleDAO = new VehicleDAO();
    private final ParkingRecordDAO recordDAO = new ParkingRecordDAO();

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
     * Throws IllegalStateException if no slot of that type is free, or if the
     * vehicle is already parked somewhere.
     */
    public ParkingRecord parkVehicle(String vehicleNumber, VehicleType type, String ownerName, String ownerContact) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                Optional<ParkingRecord> already = recordDAO.findActiveByVehicleNumber(con, vehicleNumber);
                if (already.isPresent()) {
                    throw new IllegalStateException("Vehicle " + vehicleNumber +
                            " is already parked at slot " + already.get().getSlotNumber());
                }

                Optional<ParkingSlot> freeSlot = slotDAO.allocateFreeSlot(con, type);
                if (freeSlot.isEmpty()) {
                    throw new IllegalStateException("No free " + type + " slot is available right now");
                }

                var vehicle = vehicleDAO.findOrCreate(con, vehicleNumber, type, ownerName, ownerContact);
                ParkingRecord record = recordDAO.insertEntry(con, vehicle.getVehicleId(), freeSlot.get().getSlotId());
                record.setVehicleNumber(vehicleNumber);
                record.setSlotNumber(freeSlot.get().getSlotNumber());
                record.setLocation(freeSlot.get().getLocation());

                con.commit();
                return record;
            } catch (Exception e) {
                con.rollback();
                if (e instanceof IllegalStateException ise) throw ise;
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /**
     * Un-parks a vehicle: closes its active parking record (setting exit time
     * and duration) and frees up its slot again.
     */
    public ParkingRecord unparkVehicle(String vehicleNumber) throws SQLException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                ParkingRecord active = recordDAO.findActiveByVehicleNumber(con, vehicleNumber)
                        .orElseThrow(() -> new IllegalStateException("Vehicle " + vehicleNumber + " is not currently parked"));

                ParkingRecord closed = recordDAO.closeRecord(con, active.getRecordId());
                slotDAO.markOccupied(con, active.getSlotId(), false);

                closed.setVehicleNumber(vehicleNumber);
                closed.setSlotNumber(active.getSlotNumber());
                closed.setLocation(active.getLocation());

                con.commit();
                return closed;
            } catch (Exception e) {
                con.rollback();
                if (e instanceof IllegalStateException ise) throw ise;
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /** The "vehicle finder": where is this vehicle parked right now, if at all? */
    public Optional<ParkingRecord> locateVehicle(String vehicleNumber) throws SQLException {
        return recordDAO.findActiveByVehicleNumber(vehicleNumber);
    }

    public List<ParkingRecord> listCurrentlyParked() throws SQLException {
        return recordDAO.findAllActive();
    }

    public List<ParkingRecord> listHistory(int limit) throws SQLException {
        return recordDAO.findHistory(limit);
    }

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
