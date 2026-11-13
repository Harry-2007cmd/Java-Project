package com.campus.parking;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.VehicleType;
import com.campus.parking.service.ParkingService;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * A plain text-menu version of the application, built on exactly the same
 * ParkingService as the web UI. Handy for a quick viva demo without needing
 * a browser, and shows that the business logic is not tied to the web layer.
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.campus.parking.ConsoleApp
 */
public class ConsoleApp {

    private static final ParkingService service = new ParkingService();
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Smart Campus Parking and Vehicle Finder - Console Mode");
        System.out.println("============================================================");

        boolean running = true;
        while (running) {
            printMenu();
            String choice = scanner.nextLine().trim();
            try {
                switch (choice) {
                    case "1" -> showAvailableSlots();
                    case "2" -> parkVehicle();
                    case "3" -> unparkVehicle();
                    case "4" -> findVehicle();
                    case "5" -> showOccupancyReport();
                    case "6" -> showCurrentlyParked();
                    case "0" -> running = false;
                    default -> System.out.println("Invalid choice, try again.\n");
                }
            } catch (SQLException e) {
                System.out.println("Database error: " + e.getMessage());
            } catch (IllegalArgumentException | IllegalStateException e) {
                System.out.println("! " + e.getMessage() + "\n");
            }
        }
        System.out.println("Goodbye!");
    }

    private static void printMenu() {
        System.out.println("""
                ------------------------------------------------------------
                1. Display available parking slots
                2. Park a vehicle (auto-allocate slot)
                3. Unpark a vehicle
                4. Find my vehicle
                5. Occupancy report
                6. List currently parked vehicles
                0. Exit
                ------------------------------------------------------------
                Enter choice: """);
    }

    private static void showAvailableSlots() throws SQLException {
        System.out.print("Vehicle type (CAR/BIKE/BUS/EV) or blank for all: ");
        String typeInput = scanner.nextLine().trim();
        List<ParkingSlot> slots = typeInput.isBlank()
                ? service.listAllSlots()
                : service.listAvailableSlots(VehicleType.fromString(typeInput));

        System.out.printf("%-10s %-8s %-30s %-10s%n", "Slot", "Type", "Location", "Status");
        for (ParkingSlot s : slots) {
            System.out.printf("%-10s %-8s %-30s %-10s%n",
                    s.getSlotNumber(), s.getVehicleType(), s.getLocation(), s.isOccupied() ? "Occupied" : "Free");
        }
        System.out.println();
    }

    private static void parkVehicle() throws SQLException {
        System.out.print("Vehicle number: ");
        String number = scanner.nextLine().trim().toUpperCase();
        System.out.print("Vehicle type (CAR/BIKE/BUS/EV): ");
        VehicleType type = VehicleType.fromString(scanner.nextLine().trim());
        System.out.print("Owner name: ");
        String owner = scanner.nextLine().trim();
        System.out.print("Owner contact: ");
        String contact = scanner.nextLine().trim();

        ParkingRecord record = service.parkVehicle(number, type, owner, contact);
        System.out.printf("Parked! %s -> Slot %s (%s) at %s%n%n",
                record.getVehicleNumber(), record.getSlotNumber(), record.getLocation(), record.getEntryTime());
    }

    private static void unparkVehicle() throws SQLException {
        System.out.print("Vehicle number: ");
        String number = scanner.nextLine().trim().toUpperCase();
        ParkingRecord record = service.unparkVehicle(number);
        System.out.printf("%s left slot %s. Duration: %d minutes.%n%n",
                record.getVehicleNumber(), record.getSlotNumber(), record.getDurationMinutes());
    }

    private static void findVehicle() throws SQLException {
        System.out.print("Vehicle number: ");
        String number = scanner.nextLine().trim().toUpperCase();
        var found = service.locateVehicle(number);
        if (found.isEmpty()) {
            System.out.println("Vehicle " + number + " is not currently parked on campus.\n");
            return;
        }
        var r = found.get();
        System.out.printf("%s is parked at slot %s, %s. Entry time: %s%n%n",
                r.getVehicleNumber(), r.getSlotNumber(), r.getLocation(), r.getEntryTime());
    }

    private static void showOccupancyReport() throws SQLException {
        Map<VehicleType, ParkingService.OccupancyStat> report = service.occupancyReport();
        System.out.printf("%-8s %-8s %-10s %-10s %-10s%n", "Type", "Total", "Occupied", "Free", "Occ. %");
        int totalAll = 0, occAll = 0;
        for (var entry : report.entrySet()) {
            var s = entry.getValue();
            System.out.printf("%-8s %-8d %-10d %-10d %-10.1f%n",
                    entry.getKey(), s.totalSlots, s.occupiedSlots, s.availableSlots(), s.occupancyPercent());
            totalAll += s.totalSlots;
            occAll += s.occupiedSlots;
        }
        double overallPct = totalAll == 0 ? 0 : (occAll * 100.0) / totalAll;
        System.out.printf("%nOverall occupancy: %d/%d slots (%.1f%%)%n%n", occAll, totalAll, overallPct);
    }

    private static void showCurrentlyParked() throws SQLException {
        List<ParkingRecord> list = service.listCurrentlyParked();
        if (list.isEmpty()) {
            System.out.println("No vehicles are currently parked.\n");
            return;
        }
        System.out.printf("%-14s %-8s %-30s %-20s%n", "Vehicle", "Slot", "Location", "Entry Time");
        for (ParkingRecord r : list) {
            System.out.printf("%-14s %-8s %-30s %-20s%n",
                    r.getVehicleNumber(), r.getSlotNumber(), r.getLocation(), r.getEntryTime());
        }
        System.out.println();
    }
}
