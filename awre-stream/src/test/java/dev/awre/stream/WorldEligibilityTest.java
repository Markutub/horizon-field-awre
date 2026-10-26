package dev.awre.stream;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorldEligibilityTest {
    @Test void onlyTheNetherHasACeilingAmongTheVanillaEnvironments() {
        assertTrue(WorldEligibility.hasCeiling("NETHER"));
        assertTrue(WorldEligibility.hasCeiling("nether"));
        for (String e : new String[]{"NORMAL", "THE_END", "CUSTOM", "", "something_else"}) assertFalse(WorldEligibility.hasCeiling(e), e);
        assertFalse(WorldEligibility.hasCeiling(null));
    }

    @Test void theConfigSwitchDecidesWhetherACeilingWorldIsSkipped() {
        assertTrue(WorldEligibility.skipsStreaming("NETHER", true));
        assertFalse(WorldEligibility.skipsStreaming("NETHER", false), "the operator can opt in knowing the roof will be streamed");
        assertFalse(WorldEligibility.skipsStreaming("NORMAL", true));
        assertFalse(WorldEligibility.skipsStreaming("THE_END", true));
    }
}
