package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.DialogueCleanup.Disposition;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RoleplayNoticeFilteringTest {
    private final DialogueTextNormalizer normalizer = new DialogueTextNormalizer();
    private static String log(String body) { return "[12:34:56] [Render thread/INFO]: [System] [CHAT]" + body; }
    static Stream<String> notices() {
        return Stream.of(
            "You are now Roleplaying as Synthetic Persona",
            "Set size for persona Synthetic Persona to: 1.0 (180cm, 71in, 5'11\")",
            "JourneyMap: Press [J]",
            "You have friend requests from: SyntheticAccount",
            "====== Latest News ======",
            "• [Link] Synthetic news update",
            "Your Soulstone will recharge in 5 seconds.",
            "Your Soulstone begins to glow.",
            "Showing matches to Persona name synthetic",
            "Synthetic Persona is a Persona of SyntheticAccount",
            "The key did not fit!",
            "Can you build? Yes",
            "Applicable regions: synthetic_region, synthetic_road"
        );
    }
    @ParameterizedTest @MethodSource("notices")
    void recognizesOnlyCompleteUnattributedOperationalNotices(String notice) {
        var parsed = new LogLineParser(Set.of()).parse(log(notice), 7).orElseThrow();
        assertEquals(Channel.SYSTEM, parsed.channel());
        assertTrue(parsed.observedSpeaker().isEmpty());
        var cleaned = normalizer.clean(notice, Channel.UNKNOWN);
        assertEquals(Disposition.IGNORED, cleaned.disposition());
        assertNull(cleaned.text());
    }
    @ParameterizedTest @MethodSource("notices")
    void spokenNoticeTextIsDialogueAndNeverRemovedAsASubstring(String notice) {
        String spoken = "Synthetic Speaker: \"" + notice + "\"";
        var parsed = new LogLineParser(Set.of()).parse(log("[Quiet]" + spoken), 8).orElseThrow();
        assertEquals(Channel.ROLEPLAY, parsed.channel());
        assertTrue(parsed.observedSpeaker().isEmpty(), "Channel evidence must not invent an identity");
        assertEquals(spoken, parsed.text());
        assertEquals(spoken, normalizer.clean(spoken, Channel.ROLEPLAY).text());
        String emote = "Synthetic Speaker recalls " + notice + " while walking.";
        assertEquals(emote, normalizer.clean(emote, Channel.UNKNOWN).text());
    }
    @ParameterizedTest @MethodSource("notices")
    void migrationNeverDeletesPreviouslyAttributedRoleplayEvenWhenItExactlyMatchesANotice(String notice) {
        var cleaned = normalizer.clean(notice, Channel.ROLEPLAY);
        assertEquals(Disposition.DIALOGUE, cleaned.disposition());
        assertEquals(notice, cleaned.text());
        assertEquals(cleaned, normalizer.clean(cleaned.text(), Channel.ROLEPLAY));
    }
    @ParameterizedTest @ValueSource(strings = {"Quiet", "Shout", "Local", "RP", "Roleplay", "Normal"})
    void trustedChannelAndSenderShapedTextEstablishRoleplayWithoutEstablishingIdentity(String wrapper) {
        for (String body : new String[] {"Synthetic Visitor waves.", "Synthetic Visitor says dialogue 1.",
                "Synthetic Visitor: \"Hello.\"", "[!] An unnamed stranger bows.", "*Synthetic Visitor nods.*"}) {
            var parsed = new LogLineParser(Set.of()).parse(log("[" + wrapper + "]" + body), 4).orElseThrow();
            assertEquals(Channel.ROLEPLAY, parsed.channel());
            assertTrue(parsed.observedSpeaker().isEmpty());
            assertEquals(body, parsed.text());
        }
    }
    @ParameterizedTest @ValueSource(strings = {
        "Synthetic Visitor waves.", "[Quiet]Hello", "[Quiet][Unknown] Synthetic Visitor waves.",
        "[Quiet][99:88:77] Synthetic Visitor waves.", "[Unknown][Quiet]Synthetic Visitor waves.",
        "[Quiet]Synthetic    "
    })
    void missingOrAmbiguousChannelAndSenderProofStaysUnknown(String body) {
        var parsed = new LogLineParser(Set.of()).parse(log(body), 1).orElseThrow();
        assertEquals(Channel.UNKNOWN, parsed.channel());
        assertTrue(parsed.observedSpeaker().isEmpty());
        assertFalse(parsed.channel().eligibleForAnalysis());
    }
    @ParameterizedTest @ValueSource(strings = {
        "[Quiet][PM] Synthetic Visitor: private sentinel", "[Quiet][Whisper] Synthetic Visitor waves.",
        "[RP][OOC] Synthetic Visitor: ooc sentinel", "[Local](( Synthetic Visitor waves. ))"
    })
    void privacyOverridesTrustedRoleplayAndReviewedNames(String body) {
        var parsed = new LogLineParser(Set.of("Synthetic Visitor")).parse(log(body), 1).orElseThrow();
        assertTrue(parsed.channel() == Channel.PRIVATE_MESSAGE || parsed.channel() == Channel.OOC);
        assertTrue(parsed.observedSpeaker().isEmpty());
        assertFalse(parsed.channel().eligibleForAnalysis());
        assertEquals(Disposition.EXCLUDED, normalizer.clean(body, Channel.ROLEPLAY).disposition());
    }
    @Test void reviewedNamesStillResolveOnlyReviewedObservationsIncludingTrustedRoleplay() {
        var parser = new LogLineParser(Set.of("Synthetic", "Synthetic Visitor"));
        for (String prefix : new String[] {"", "[Quiet]"}) {
            var parsed = parser.parse(log(prefix + "Synthetic Visitor waves."), 1).orElseThrow();
            assertEquals(Channel.ROLEPLAY, parsed.channel());
            assertEquals("Synthetic Visitor", parsed.observedSpeaker().orElseThrow());
        }
    }
    @Test void personaSearchCannotCreateSpeakerObservationEvenIfNameWasReviewed() {
        var parser = new LogLineParser(Set.of("Synthetic Persona"));
        var notice = parser.parse(log("Synthetic Persona is a Persona of SyntheticAccount"), 1).orElseThrow();
        assertEquals(Channel.SYSTEM, notice.channel());
        assertTrue(notice.observedSpeaker().isEmpty());
        var spoken = parser.parse(log("Synthetic Persona: \"is a Persona of SyntheticAccount\""), 1).orElseThrow();
        assertEquals(Channel.ROLEPLAY, spoken.channel());
        assertEquals("Synthetic Persona", spoken.observedSpeaker().orElseThrow());
    }
    @Test void nearMissTemplatesRemainReviewableRatherThanBeingDroppedByKeywords() {
        for (String text : new String[] {"The key did not fit, so the stranger waited.",
                "Can you build? No, I cannot build this bridge.", "JourneyMap might be a character name.",
                "[Link] An unknown character watches.", "Your Soulstone sings a strange tune.",
                "Synthetic Persona is a Persona of someone, perhaps.", "Applicable regions are described in this book.",
                "Applicable regions: synthetic_region, claims the stranger.", "Applicable regions: [synthetic_region",
                "Applicable regions: synthetic_region]", "You are now Roleplaying as Synthetic Persona. She laughs.",
                "Showing matches to Persona name synthetic. A stranger waits.",
                "* [Link] silently folds the map.*", "- [Link] steps back."}) {
            assertEquals(Disposition.DIALOGUE, normalizer.clean(text, Channel.UNKNOWN).disposition());
        }
        assertEquals(Disposition.IGNORED, normalizer.clean(". [Link] Synthetic news update", Channel.UNKNOWN).disposition());
    }
}
