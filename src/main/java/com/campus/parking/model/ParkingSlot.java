package com.campus.parking.model;

/**
 * A single physical parking slot on campus.
 */
public class ParkingSlot {
    private int slotId;
    private String slotNumber;
    private VehicleType vehicleType;
    private String location;
    private boolean occupied;

    public ParkingSlot() {
    }

    public ParkingSlot(int slotId, String slotNumber, VehicleType vehicleType, String location, boolean occupied) {
        this.slotId = slotId;
        this.slotNumber = slotNumber;
        this.vehicleType = vehicleType;
        this.location = location;
        this.occupied = occupied;
    }

    public int getSlotId() {
        return slotId;
    }

    public void setSlotId(int slotId) {
        this.slotId = slotId;
    }

    public String getSlotNumber() {
        return slotNumber;
    }

    public void setSlotNumber(String slotNumber) {
        this.slotNumber = slotNumber;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(VehicleType vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public boolean isOccupied() {
        return occupied;
    }

    public void setOccupied(boolean occupied) {
        this.occupied = occupied;
    }
}
