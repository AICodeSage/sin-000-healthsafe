package co.wethinkcode.healthsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StaffingServiceAppTest {
    private final StaffingServiceApp.Ward ward = new StaffingServiceApp.Ward(
            "W-05", "East Wing", "Paediatrics", 5, null);

    @Test
    void addsEmergencyRolesAtEscalationThresholds() {
        StaffingServiceApp.Schedule routine = StaffingServiceApp.Schedule.create(ward, 0);
        StaffingServiceApp.Schedule elevated = StaffingServiceApp.Schedule.create(ward, 6);
        StaffingServiceApp.Schedule codeBlue = StaffingServiceApp.Schedule.create(ward, 8);

        assertEquals(1, routine.requiredDoctors());
        assertEquals(3, elevated.requiredDoctors());
        assertEquals(4, codeBlue.requiredDoctors());
        assertEquals("Code Blue support physician", codeBlue.onCallRoles().get(3));
    }
}
