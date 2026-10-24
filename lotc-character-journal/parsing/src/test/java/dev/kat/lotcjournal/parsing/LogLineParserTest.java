package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LogLineParserTest {
    private final LogLineParser parser = new LogLineParser(Set.of("Mira", "Mira Vale"));
    private String line(String text) {
        return "[23:59:59] [Render thread/INFO]: [System] [CHAT] [23:59:59] " + text;
    }

    @Test void preservesSourceClockAndLongestReviewedName() {
        var result = parser.parse(line("§aMira Vale§r waves."), 43).orElseThrow();
        assertEquals(Channel.ROLEPLAY, result.channel());
        assertEquals("Mira Vale", result.observedSpeaker().orElseThrow());
        assertEquals(43, result.sourceLine());
        assertEquals("23:59:59", result.clock().toString());
        assertEquals("Mira Vale waves.", result.text());
    }

    @ParameterizedTest @ValueSource(strings = {
        "[Mira -> Other] Mira waves.", "[Mira → Other] hello", "From Other: hello", "To Other: hello"
    })
    void privateMessagesOverrideKnownNames(String text) {
        var result = parser.parse(line(text), 1).orElseThrow();
        assertEquals(Channel.PRIVATE_MESSAGE, result.channel());
        assertFalse(result.channel().eligibleForAnalysis());
    }

    @ParameterizedTest @ValueSource(strings = {
        "[LOOC] Mira: hello", "[OOC] Mira: hello", "[G] Mira: hello", "(( Mira waves. ))"
    })
    void oocIsExcluded(String text) {
        assertEquals(Channel.OOC, parser.parse(line(text), 1).orElseThrow().channel());
    }

    @ParameterizedTest @ValueSource(strings = {
        "Mirae waves.", "Unreviewed Name waves.", "[!] An unnamed stranger waves.",
        "[NewChannel] Mira waves."
    })
    void uncertainIdentityOrChannelStaysUnknown(String text) {
        var result = parser.parse(line(text), 1).orElseThrow();
        assertEquals(Channel.UNKNOWN, result.channel());
        assertTrue(result.observedSpeaker().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {
        "not a log", "[24:90:00] [Render thread/INFO]: [CHAT] Mira waves.",
        "[12:00:00] [Render thread/ERROR]: [CHAT] Mira waves.",
        "[12:00:00] [Render thread/INFO]: Mira waves."
    })
    void malformedOrNonChatLinesAreNotImported(String raw) {
        assertTrue(parser.parse(raw, 1).isEmpty());
    }

    @Test void refusesInvalidSourceCoordinate() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(line("Mira waves."), 0));
    }
}

