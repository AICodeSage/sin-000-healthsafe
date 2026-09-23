package co.wethinkcode.healthsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WardCsvLoaderTest {

    @Test
    void cleansBadValuesAndRetainsTheFirstDuplicate() {
        List<WardRecord> wards = WardCsvLoader.load();

        assertEquals(17, wards.size());
        WardRecord duplicate = find(wards, "W-05");
        assertEquals("Paediatrics", duplicate.department());
        assertEquals(5, duplicate.bedsAvailable());
        assertTrue(duplicate.notes().contains("duplicate source row 7 ignored"));

        WardRecord invalidBeds = find(wards, "W-13");
        assertNull(invalidBeds.bedsAvailable());
        assertTrue(invalidBeds.notes().contains("outside the accepted range"));
    }

    @Test
    void normalizesWardIdsForLookup() {
        assertEquals("W-05", WardCsvLoader.normalizeWardId(" w-05 "));
    }

    private WardRecord find(List<WardRecord> wards, String wardId) {
        return wards.stream().filter(ward -> ward.wardId().equals(wardId)).findFirst().orElseThrow();
    }
}
