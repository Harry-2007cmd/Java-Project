package com.campus.parking.model;

/**
 * A vehicle registered in the system (owned by a student/staff member).
 */
public class Vehicle {
    private int vehicleId;
    private String vehicleNumber;
    private VehicleType vehicleType;
    private String ownerName;
    private String ownerContact;

    public Vehicle() {
    }

    public Vehicle(int vehicleId, String vehicleNumber, VehicleType vehicleType, String ownerName, String ownerContact) {
        this.vehicleId = vehicleId;
        this.vehicleNumber = vehicleNumber;
        this.vehicleType = vehicleType;
        this.ownerName = ownerName;
        this.ownerContact = ownerContact;
    }

    public int getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public void setVehicleType(VehicleType vehicleType) {
        this.vehicleType = vehicleType;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwnerContact() {
        return ownerContact;
    }

    public void setOwnerContact(String ownerContact) {
        this.ownerContact = ownerContact;
    }
}
