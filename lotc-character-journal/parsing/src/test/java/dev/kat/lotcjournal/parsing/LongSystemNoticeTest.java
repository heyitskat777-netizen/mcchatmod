package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.DialogueCleanup.Disposition;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LongSystemNoticeTest {
    @Test void validAndMalformedLargeNoticeListsUseBoundedTimeAndConstantStack() {
        String regions = "Applicable regions: " + String.join(", ", Collections.nCopies(10_000, "r"));
        String friends = "You have friend requests from: " + String.join(", ", Collections.nCopies(2_000, "SyntheticAccount"));
        assertTrue(regions.getBytes(StandardCharsets.UTF_8).length < 65_536);
        assertTrue(friends.getBytes(StandardCharsets.UTF_8).length < 65_536);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var normalizer = new DialogueTextNormalizer();
            var parser = new LogLineParser(Set.of());
            for (String valid : new String[] {regions, friends}) {
                var cleaned = normalizer.clean(valid, Channel.UNKNOWN);
                assertEquals(Disposition.IGNORED, cleaned.disposition());
                assertNull(cleaned.text());
                var parsed = parser.parse("[12:34:56] [Render thread/INFO]: [CHAT]" + valid, 1).orElseThrow();
                assertEquals(Channel.SYSTEM, parsed.channel());
                // Existing attributed dialogue remains protected even for very large exact templates.
                assertEquals(valid, normalizer.clean(valid, Channel.ROLEPLAY).text());
            }
            for (String malformed : new String[] {regions + ", invalid region text", friends + ", invalid account name",
                    regions + ",", friends + ", ", "Applicable regions: [" + regions.substring("Applicable regions: ".length())}) {
                var cleaned = normalizer.clean(malformed, Channel.UNKNOWN);
                assertEquals(Disposition.DIALOGUE, cleaned.disposition());
                assertEquals(malformed, cleaned.text());
            }
        });
    }
}
