package co.wethinkcode.healthsafe;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads the legacy export without allowing a malformed value to fail the service. */
final class WardCsvLoader {
    private static final int MAX_REASONABLE_BED_COUNT = 500;

    private WardCsvLoader() {
    }

    static List<WardRecord> load() {
        InputStream resource = WardCsvLoader.class.getResourceAsStream("/wards-outdated.csv");
        if (resource == null) {
            throw new IllegalStateException("Missing wards-outdated.csv resource");
        }

        Map<String, WardRecord> wards = new LinkedHashMap<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            for (int index = 1; index < rows.size(); index++) {
                WardRecord record = clean(rows.get(index), index + 1);
                if (record == null) {
                    continue;
                }
                WardRecord previous = wards.putIfAbsent(record.wardId(), record);
                if (previous != null) {
                    wards.put(record.wardId(), withDuplicateNote(previous, index + 1));
                }
            }
        } catch (IOException | CsvException exception) {
            throw new IllegalStateException("Could not read wards-outdated.csv", exception);
        }
        return List.copyOf(wards.values());
    }

    static String normalizeWardId(String value) {
        return normalizeWhitespace(value).toUpperCase(Locale.ROOT);
    }

    private static WardRecord clean(String[] row, int lineNumber) {
        if (row.length < 4) {
            return null;
        }
        String wardId = normalizeWardId(row[0]);
        if (wardId.isBlank()) {
            return null;
        }

        List<String> notes = new ArrayList<>();
        String wing = normalizeName(row[1]);
        String department = normalizeDepartment(row[2]);
        Integer bedsAvailable = normalizeBedCount(row[3], notes);
        if (wing == null) {
            notes.add("wing was missing");
        }
        if (department == null) {
            notes.add("department was missing");
        }
        return new WardRecord(wardId, wing, department, bedsAvailable,
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    private static WardRecord withDuplicateNote(WardRecord record, int lineNumber) {
        String duplicateNote = "duplicate source row " + lineNumber + " ignored; first record retained";
        String notes = record.notes() == null ? duplicateNote : record.notes() + "; " + duplicateNote;
        return new WardRecord(record.wardId(), record.wing(), record.department(), record.bedsAvailable(), notes);
    }

    private static Integer normalizeBedCount(String rawValue, List<String> notes) {
        String value = normalizeWhitespace(rawValue);
        if (isPlaceholder(value)) {
            return null;
        }
        try {
            int beds = Integer.parseInt(value);
            if (beds < 0 || beds > MAX_REASONABLE_BED_COUNT) {
                notes.add("bedsAvailable was outside the accepted range ('" + value + "')");
                return null;
            }
            return beds;
        } catch (NumberFormatException exception) {
            notes.add("bedsAvailable was non-numeric ('" + value + "')");
            return null;
        }
    }

    private static String normalizeDepartment(String rawValue) {
        String value = normalizeName(rawValue);
        if (value == null) {
            return null;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "pediatrics" -> "Paediatrics";
            case "icu" -> "ICU";
            default -> value;
        };
    }

    private static String normalizeName(String rawValue) {
        String value = normalizeWhitespace(rawValue);
        if (isPlaceholder(value)) {
            return null;
        }
        return titleCase(value);
    }

    private static boolean isPlaceholder(String value) {
        return value.isBlank() || switch (value.toLowerCase(Locale.ROOT)) {
            case "n/a", "tbd", "unknown", "-", "nan" -> true;
            default -> false;
        };
    }

    private static String normalizeWhitespace(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder();
        for (String word : value.toLowerCase(Locale.ROOT).split(" ")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
