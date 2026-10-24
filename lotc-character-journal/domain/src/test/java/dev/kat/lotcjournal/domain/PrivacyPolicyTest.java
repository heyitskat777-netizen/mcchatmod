package dev.kat.lotcjournal.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyPolicyTest {
    @Test void onlyConfirmedRoleplayIsEligibleForAnalysis() {
        for (Channel channel : Channel.values())
            assertEquals(channel == Channel.ROLEPLAY, channel.eligibleForAnalysis(), channel.name());
    }
}

