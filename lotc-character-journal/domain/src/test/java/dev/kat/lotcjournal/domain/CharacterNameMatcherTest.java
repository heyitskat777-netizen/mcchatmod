package dev.kat.lotcjournal.domain;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CharacterNameMatcherTest {
    @Test void anchoredSpeechAndActionsRetainTheEntireObservedMultiwordName() {
        for (String suffix : List.of(": \"Synthetic dialogue.\"", " says \"Synthetic dialogue.\"", " says: hello.", " asks: why?", " whispers to the room.")) {
            var actor = CharacterNameMatcher.actor("Synthetic Visitor" + suffix).orElseThrow();
            assertEquals("Synthetic Visitor", actor.name()); assertEquals(CharacterEvidenceKind.SPEAKER, actor.kind());
        }
        for (String text : List.of("Synthetic Visitor waves.", "Synthetic Visitor smiles: softly.", "*Synthetic Visitor folds a synthetic map.*")) {
            var actor = CharacterNameMatcher.actor(text).orElseThrow();
            assertEquals("Synthetic Visitor", actor.name()); assertEquals(CharacterEvidenceKind.ACTION, actor.kind());
        }
        assertEquals("Élian O'Vale", CharacterNameMatcher.actor("Élian O'Vale asks a question.").orElseThrow().name());
        assertEquals("Synthetic-Name", CharacterNameMatcher.actor("Synthetic-Name nods.").orElseThrow().name());
    }

    @Test void QuotedMentionsAndUnnamedActorsAreNotConvertedIntoNamedSpeakers() {
        for (String text : List.of("\"Synthetic Visitor waves.\"", "[a distant sound] Synthetic Visitor waves.",
                "An unnamed stranger waves.", "The traveller says hello.", "Someone nods.", "You smile.",
                "A letter mentions Synthetic Visitor.", "Synthetic Visitor", "Synthetic Visitor uncertain wording."))
            assertTrue(CharacterNameMatcher.actor(text).isEmpty(), () -> "Synthetic ambiguous input: " + text);
        assertTrue(CharacterNameMatcher.actor(null).isEmpty()); assertTrue(CharacterNameMatcher.actor(" ").isEmpty());
    }

    @Test void mentionsUseLiteralWholeTokenBoundariesRatherThanSubstringOrPatternSyntax() {
        for (String text : List.of("Ash waits.", "A note for (Ash).", "Ash's map.", "Ash’s map.", "ASH!", "Ash-returns"))
            assertTrue(CharacterNameMatcher.mentions(text, "Ash"));
        for (String text : List.of("Ashen waits.", "Smash the stone.", "_Ash_", "Ash2", "PreAsh", "Ash'lyn waits.", "Ash’lyn waits.", "Ash'ss waits."))
            assertFalse(CharacterNameMatcher.mentions(text, "Ash"));
        assertTrue(CharacterNameMatcher.mentions("A note for Sir Vale.", "Sir Vale"));
        assertFalse(CharacterNameMatcher.mentions("A note for Sir Valerian.", "Sir Vale"));
        for (String name : List.of("Literal %_\\'", "[Synthetic]", "Name.*", "A+B")) {
            assertTrue(CharacterNameMatcher.mentions("A note: " + name + "!", name));
            assertFalse(CharacterNameMatcher.mentions("A note without a matching alias.", name));
        }
    }

    @Test void unicodeNormalizationAndCaseMatchingDoNotEraseAdjacentUnicodeTokenBoundaries() {
        assertEquals(CharacterNameMatcher.key("Élian"), CharacterNameMatcher.key("E\u0301LIAN"));
        assertTrue(CharacterNameMatcher.mentions("A note to E\u0301LIAN.", "Élian"));
        assertFalse(CharacterNameMatcher.mentions("Éliana", "Élian"));
        assertFalse(CharacterNameMatcher.mentions("x\u0301Élian", "Élian"));
        assertFalse(CharacterNameMatcher.mentions("山Élian", "Élian"));
        assertFalse(CharacterNameMatcher.mentions("Élian山", "Élian"));
        assertFalse(CharacterNameMatcher.mentions("Ash\u0903", "Ash"));
        assertFalse(CharacterNameMatcher.mentions("Ash\u20dd", "Ash"));
        assertFalse(CharacterNameMatcher.mentions(null, "Élian"));
        assertFalse(CharacterNameMatcher.mentions("Élian", " "));
    }

    @Test void overlongAndUnboundedUncertainInputsRemainUnattributedWithinAStageBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertTrue(CharacterNameMatcher.actor("S".repeat(97) + " waves.").isEmpty());
            assertTrue(CharacterNameMatcher.actor("Synthetic ".repeat(9) + "Visitor waves.").isEmpty());
            assertTrue(CharacterNameMatcher.actor("Synthetic ".repeat(6000) + "uncertain.").isEmpty());
            assertTrue(CharacterNameMatcher.actor("Alice saysЖ a synthetic word.").isEmpty());
            assertTrue(CharacterNameMatcher.actor("Alice wavesé a synthetic word.").isEmpty());
            assertFalse(CharacterNameMatcher.mentions("x".repeat(65_000), "Synthetic Visitor"));
        });
    }
}
