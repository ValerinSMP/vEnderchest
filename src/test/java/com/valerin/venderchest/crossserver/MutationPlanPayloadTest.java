package com.valerin.venderchest.crossserver;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationPlanPayloadTest {

    private final Gson gson = new Gson();

    @Test
    void historicalV1WithoutSchemaVersionKeepsOriginalMeaning() {
        MutationPlan plan = gson.fromJson("{\"playerSlots\":[]}", MutationPlan.class);

        assertEquals(1, plan.schemaVersion());
        assertTrue(plan.isLegacy());
    }

    @Test
    void unknownFutureSchemaFailsClosed() {
        assertThrows(RuntimeException.class, () -> gson.fromJson(
                "{\"schemaVersion\":3,\"playerSlots\":[]}", MutationPlan.class));
    }
}
