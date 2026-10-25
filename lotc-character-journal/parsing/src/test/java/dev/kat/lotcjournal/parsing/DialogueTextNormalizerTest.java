package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.DialogueCleanup.Disposition;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DialogueTextNormalizerTest {
    private final DialogueTextNormalizer normalizer = new DialogueTextNormalizer();
    @ParameterizedTest @ValueSource(strings = {"[Quiet]", "[Shout]", "[Local]", "[RP]", "[Roleplay]", "[Normal]", "[quiet]"})
    void removesOnlyRecognizedLeadingDecorations(String prefix) {
        String body = "Synthetic Speaker: \"A [Quiet] note at 12:00:00.\"";
        var cleaned = normalizer.clean("§a" + prefix + "§r " + body, Channel.UNKNOWN);
        assertEquals(Disposition.DIALOGUE, cleaned.disposition()); assertEquals(body, cleaned.text());
        assertEquals(cleaned, normalizer.clean(cleaned.text(), Channel.UNKNOWN));
    }
    @Test void acceptsAdjacentChatTagAndPreservesDialogueAndUnresolvedIdentity() {
        var parsed = new LogLineParser(Set.of()).parse(
            "[01:23:45] [Render thread/INFO]: [System] [CHAT][Quiet] Synthetic Speaker: \"Hello.\"", 9).orElseThrow();
        assertEquals("Synthetic Speaker: \"Hello.\"", parsed.text());
        assertEquals(Channel.ROLEPLAY, parsed.channel()); assertTrue(parsed.observedSpeaker().isEmpty());
        assertEquals(9, parsed.sourceLine()); assertEquals("01:23:45", parsed.clock().toString());
    }
    @ParameterizedTest @ValueSource(strings = {"[Quiet] [PM] PRIVATE_SENTINEL", "[RP][OOC] PRIVATE_SENTINEL", "[Whisper] PRIVATE_SENTINEL", "[Quiet] (( PRIVATE_SENTINEL"})
    void privacyRemainsExcludedBeforeAndAfterCleanup(String payload) {
        var result = normalizer.clean(payload, Channel.UNKNOWN);
        assertEquals(Disposition.EXCLUDED, result.disposition()); assertNull(result.text());
    }
    @ParameterizedTest @ValueSource(strings = {"[Replay Mod] started recording", "[Quiet][Aviary] delivery notice", " ", "[Quiet] §r"})
    void ignoresKnownSystemAndEmptyPayloads(String payload) {
        var result = normalizer.clean(payload, Channel.UNKNOWN);
        assertEquals(Disposition.IGNORED, result.disposition()); assertNull(result.text());
    }
    @Test void preservesBracketedActionsMalformedClocksAndTextInsideSpeech() {
        for (String text : new String[]{"[draws a sword] Synthetic Speaker waits.", "[UnknownChannel] Synthetic Speaker waits.",
                "[99:88:77] Synthetic Speaker waits.", "Synthetic Speaker reads '[CHAT] [PM]' aloud."}) {
            assertEquals(text, normalizer.clean(text, Channel.UNKNOWN).text());
        }
    }
}
