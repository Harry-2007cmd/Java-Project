package com.campus.parking;

import com.campus.parking.model.ParkingRecord;
import com.campus.parking.model.ParkingSlot;
import com.campus.parking.model.User;
import com.campus.parking.model.VehicleType;
import com.campus.parking.service.AuthService;
import com.campus.parking.service.ParkingService;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * A plain text-menu version of the application, built on exactly the same
 * services as the web UI (including login and billing). Handy for a quick viva
 * demo without a browser, and shows the business logic is not tied to the web layer.
 *
 * Run with: mvn exec:java -Dexec.mainClass=com.campus.parking.ConsoleApp
 * Demo accounts: admin / admin123 and student1 / student123
 */
public class ConsoleApp {

    private static final ParkingService service = new ParkingService();
    private static final AuthService auth = new AuthService();
    private static final Scanner scanner = new Scanner(System.in);

    private static User currentUser;

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" Smart Campus Parking and Vehicle Finder - Console Mode");
        System.out.println("============================================================");

        if (!loginLoop()) {
            System.out.println("Goodbye!");
            return;
        }

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
                    case "7" -> showRates();
                    case "8" -> { if (requireAdmin()) addSlot(); }
                    case "9" -> { if (requireAdmin()) deleteSlot(); }
                    case "10" -> { if (requireAdmin()) showRevenue(); }
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

    /** Asks for username/password until it works; blank username quits. */
    private static boolean loginLoop() {
        while (true) {
            System.out.print("Username (blank to quit): ");
            String username = scanner.nextLine().trim();
            if (username.isEmpty()) return false;
            System.out.print("Password: ");
            String password = scanner.nextLine();
            try {
                currentUser = auth.login(username, password);
                System.out.println("Welcome, " + currentUser.getFullName() + " (" + currentUser.getRole() + ")\n");
                return true;
            } catch (SQLException e) {
                System.out.println("Database error: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.out.println("! " + e.getMessage() + "\n");
            }
        }
    }

    /** null = no restriction (admin); a student only ever sees/touches their own vehicles. */
    private static Integer scope() {
        return currentUser.isAdmin() ? null : currentUser.getUserId();
    }

    private static boolean requireAdmin() {
        if (!currentUser.isAdmin()) {
            System.out.println("! Admin access required\n");
            return false;
        }
        return true;
    }

    private static void printMenu() {
        System.out.println("""
                ------------------------------------------------------------
                1. Display available parking slots
                2. Park a vehicle (auto-allocate slot)
                3. Unpark a vehicle (shows fee)
                4. Find my vehicle
                5. Occupancy report
                6. List currently parked vehicles
                7. Show parking rates
                8. [Admin] Add a slot
                9. [Admin] Delete a slot
                10. [Admin] Total revenue
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
        System.out.print("Owner name (blank = " + currentUser.getFullName() + "): ");
        String owner = scanner.nextLine().trim();
        if (owner.isEmpty()) owner = currentUser.getFullName();
        System.out.print("Owner contact: ");
        String contact = scanner.nextLine().trim();

        ParkingRecord record = service.parkVehicle(number, type, owner, contact, scope());
        System.out.printf("Parked! %s -> Slot %s (%s) at %s%n%n",
                record.getVehicleNumber(), record.getSlotNumber(), record.getLocation(), record.getEntryTime());
    }

    private static void unparkVehicle() throws SQLException {
        System.out.print("Vehicle number: ");
        String number = scanner.nextLine().trim().toUpperCase();
        ParkingRecord record = service.unparkVehicle(number, scope());
        System.out.printf("%s left slot %s. Duration: %d minutes. Fee: Rs.%s%n%n",
                record.getVehicleNumber(), record.getSlotNumber(), record.getDurationMinutes(), record.getFeeAmount());
    }

    private static void findVehicle() throws SQLException {
        System.out.print("Vehicle number: ");
        String number = scanner.nextLine().trim().toUpperCase();
        var found = service.locateVehicle(number, scope());
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
        List<ParkingRecord> list = service.listCurrentlyParked(scope());
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

    private static void showRates() throws SQLException {
        System.out.println("Rate per started hour (minimum 1 hour):");
        service.listRates().forEach((type, rate) -> System.out.printf("  %-6s Rs.%s%n", type, rate));
        System.out.println();
    }

    private static void addSlot() throws SQLException {
        System.out.print("Slot number (e.g. A-04): ");
        String number = scanner.nextLine().trim();
        System.out.print("Vehicle type (CAR/BIKE/BUS/EV): ");
        VehicleType type = VehicleType.fromString(scanner.nextLine().trim());
        System.out.print("Location: ");
        String location = scanner.nextLine().trim();
        ParkingSlot slot = service.addSlot(number, type, location);
        System.out.println("Added slot " + slot.getSlotNumber() + " (id " + slot.getSlotId() + ").\n");
    }

    private static void deleteSlot() throws SQLException {
        System.out.print("Slot id to delete: ");
        int id;
        try {
            id = Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Slot id must be a number");
        }
        service.deleteSlot(id);
        System.out.println("Deleted slot " + id + ".\n");
    }

    private static void showRevenue() throws SQLException {
        System.out.println("Total fees collected: Rs." + service.totalRevenue() + "\n");
    }
}
