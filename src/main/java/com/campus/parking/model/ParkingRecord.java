package com.campus.parking.model;

import java.time.LocalDateTime;

/**
 * One parking session: a vehicle occupying a slot from entryTime until
 * (optionally) exitTime. While the vehicle is still parked, exitTime and
 * durationMinutes are null - this is what lets the "vehicle finder" feature
 * locate a currently parked vehicle.
 */
public class ParkingRecord {
    private int recordId;
    private int vehicleId;
    private int slotId;
    private LocalDateTime entryTime;
    private LocalDateTime exitTime;
    private Long durationMinutes;
    private java.math.BigDecimal feeAmount;   // set when the vehicle exits

    // Extra display-only fields populated by joined queries (not stored directly)
    private String vehicleNumber;
    private String slotNumber;
    private String location;
    private String ownerName;
    private String vehicleType;
    private Long liveMinutes;      // minutes since entry, measured by the database (meaningful for active records)
    private Integer ownerUserId;   // the student account the vehicle belongs to (null if none)

    public ParkingRecord() {
    }

    public int getRecordId() {
        return recordId;
    }

    public void setRecordId(int recordId) {
        this.recordId = recordId;
    }

    public int getVehicleId() {
        return vehicleId;
    }

    public void setVehicleId(int vehicleId) {
        this.vehicleId = vehicleId;
    }

    public int getSlotId() {
        return slotId;
    }

    public void setSlotId(int slotId) {
        this.slotId = slotId;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public void setEntryTime(LocalDateTime entryTime) {
        this.entryTime = entryTime;
    }

    public LocalDateTime getExitTime() {
        return exitTime;
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
    }

    public Long getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Long durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(Integer ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public java.math.BigDecimal getFeeAmount() {
        return feeAmount;
    }

    public void setFeeAmount(java.math.BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public String getSlotNumber() {
        return slotNumber;
    }

    public void setSlotNumber(String slotNumber) {
        this.slotNumber = slotNumber;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getVehicleType() { return vehicleType; }
    public void setVehicleType(String vehicleType) { this.vehicleType = vehicleType; }
    public Long getLiveMinutes() { return liveMinutes; }
    public void setLiveMinutes(Long liveMinutes) { this.liveMinutes = liveMinutes; }

    public boolean isActive() {
        return exitTime == null;
    }
}
