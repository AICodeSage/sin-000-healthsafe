package co.wethinkcode.healthsafe;

/** A cleaned ward record. A null value represents data that could not be trusted. */
public record WardRecord(
        String wardId,
        String wing,
        String department,
        Integer bedsAvailable,
        String notes) {
}
