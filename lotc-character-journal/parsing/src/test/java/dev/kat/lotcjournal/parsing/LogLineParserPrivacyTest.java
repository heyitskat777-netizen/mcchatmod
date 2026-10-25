package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LogLineParserPrivacyTest {
    private final LogLineParser reviewed = new LogLineParser(Set.of("Mira"));
    private String line(String body) {
        return "[23:59:59] [Render thread/INFO]: [System] [CHAT] " + body;
    }

    @ParameterizedTest @ValueSource(strings = {
        "§a[PM]§r Mira: synthetic private sentinel", "[MSG]Mira: synthetic private sentinel",
        "[Whisper] Mira: synthetic private sentinel", "[Tell] Mira: synthetic private sentinel",
        "[DM] Mira: synthetic private sentinel", "[Private: Mira] synthetic private sentinel",
        "[Rank] [PM from Mira] synthetic private sentinel",
        "Mira -> Other: synthetic private sentinel", "Mira → Other: synthetic private sentinel",
        "  \u001b[31mFrom Mira:\u001b[0m synthetic private sentinel"
    })
    void explicitPrivatePrefixesOverrideReviewedNames(String body) {
        var observation = reviewed.parse(line(body), 11).orElseThrow();
        assertEquals(Channel.PRIVATE_MESSAGE, observation.channel());
        assertTrue(observation.observedSpeaker().isEmpty());
        assertFalse(observation.channel().eligibleForAnalysis());
        assertEquals(11, observation.sourceLine());
    }

    @ParameterizedTest @ValueSource(strings = {
        "§7[LOOC]§rMira: synthetic ooc sentinel", "[OOC]Mira: synthetic ooc sentinel",
        "[Local OOC] Mira: synthetic ooc sentinel", "[L-OOC] Mira: synthetic ooc sentinel",
        "[Rank] [OOC] Mira: synthetic ooc sentinel", " \u001b[36m[GLOBAL]\u001b[0m Mira: synthetic ooc sentinel"
    })
    void explicitOutOfCharacterPrefixesRemainExcluded(String body) {
        var observation = reviewed.parse(line(body), 2).orElseThrow();
        assertEquals(Channel.OOC, observation.channel());
        assertTrue(observation.observedSpeaker().isEmpty());
        assertFalse(observation.channel().eligibleForAnalysis());
    }

    @Test void defaultUnreviewedNameNeverBecomesCharacterOrAnalysisInput() {
        var observation = new LogLineParser(Set.of()).parse(line("Mira waves."), 4).orElseThrow();
        assertEquals(Channel.UNKNOWN, observation.channel());
        assertTrue(observation.observedSpeaker().isEmpty());
        assertFalse(observation.channel().eligibleForAnalysis());
    }

    @Test void malformedEmbeddedClockStaysUnresolvedInsteadOfAttributingFollowingName() {
        var observation = reviewed.parse(line("[99:88:77] Mira waves."), 3).orElseThrow();
        assertEquals(Channel.UNKNOWN, observation.channel());
        assertTrue(observation.observedSpeaker().isEmpty());
        assertEquals("[99:88:77] Mira waves.", observation.text());
        assertEquals(LocalTime.of(23, 59, 59), observation.clock());
    }

    @Test void midnightClocksAndRepeatedTextRetainIndependentSourceCoordinates() {
        var first = reviewed.parse(line("Mira waves."), 31).orElseThrow();
        var second = reviewed.parse(line("Mira waves.").replace("23:59:59", "00:00:00"), 32).orElseThrow();
        assertEquals(first.text(), second.text());
        assertEquals(31, first.sourceLine());
        assertEquals(32, second.sourceLine());
        assertEquals(LocalTime.of(23, 59, 59), first.clock());
        assertEquals(LocalTime.MIDNIGHT, second.clock());
        assertNotEquals(first, second);
    }

    @Test void quotedPrivateTagInsideRoleplayDoesNotBecomeAChannelPrefix() {
        var observation = reviewed.parse(line("Mira reads the sign '[PM] synthetic'."), 1).orElseThrow();
        assertEquals(Channel.ROLEPLAY, observation.channel());
        assertEquals("Mira", observation.observedSpeaker().orElseThrow());
    }

    @Test void colorCleanupDoesNotClaimAnUnknownChannel() {
        var observation = reviewed.parse(line("§a[UnknownChannel]§r Mira waves."), 1).orElseThrow();
        assertEquals(Channel.UNKNOWN, observation.channel());
        assertTrue(observation.observedSpeaker().isEmpty());
    }
}
