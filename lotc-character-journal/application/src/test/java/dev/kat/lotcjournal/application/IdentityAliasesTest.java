package dev.kat.lotcjournal.application;

import dev.kat.lotcjournal.domain.CharacterIdentity;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityAliasesTest {
    @Test void fullDomainSizedAliasSetRoundTripsWithoutLosingDelimitersBackslashesOrUnicode() {
        var aliases = new HashSet<String>();
        for (int index = 0; index < 200; index++) {
            var text = new StringBuilder(String.format(Locale.ROOT, "Synthetic %03d;\\Ω ", index));
            while (text.length() < 96) text.append(switch (text.length() % 3) { case 0 -> ';'; case 1 -> '\\'; default -> 'Ω'; });
            aliases.add(text.toString());
        }
        assertEquals(200, aliases.size()); assertTrue(aliases.stream().allMatch(alias -> alias.length() == 96));
        String encoded = IdentityAliases.format(aliases);
        assertTrue(encoded.length() > 4096, "The former UI bound could silently truncate a valid domain value");
        assertTrue(encoded.length() <= 40_000);
        assertEquals(aliases, IdentityAliases.parse(encoded));
    }

    @Test void editorEncodingPreservesActualAliasWhitespaceAndEscapesOnlyDelimitersAndBackslashes() {
        String alias = "  Synthetic Ω;\\ account  ";
        assertEquals("  Synthetic Ω\\;\\\\ account  ", IdentityAliases.format(Set.of(alias)));
        assertEquals(Set.of(alias), IdentityAliases.parse(IdentityAliases.format(Set.of(alias))));
        assertEquals(Set.of(), IdentityAliases.parse("")); assertEquals("", IdentityAliases.format(Set.of()));
        assertEquals(Set.of("Synthetic Name"), IdentityAliases.parse("Synthetic Name;Synthetic Name"));
    }

    @Test void malformedEscapesEmptyAliasesAndOutOfDomainBoundsAreRejected() {
        for (String invalid : List.of(";Synthetic", "Synthetic;", "Synthetic;;Other", " ", "Synthetic;\t",
                "Synthetic\\q", "Synthetic\\", "Synthetic\\n", "x".repeat(97), "x".repeat(40_001)))
            assertThrows(IllegalArgumentException.class, () -> IdentityAliases.parse(invalid), () -> "Invalid synthetic editor input length " + invalid.length());
        var tooMany = new HashSet<String>();
        for (int index = 0; index < 201; index++) tooMany.add("Synthetic " + index);
        assertThrows(IllegalArgumentException.class, () -> IdentityAliases.parse(String.join(";", tooMany)));
        assertThrows(IllegalArgumentException.class, () -> IdentityAliases.format(tooMany));
        assertThrows(IllegalArgumentException.class, () -> IdentityAliases.format(Set.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> IdentityAliases.format(Set.of("x".repeat(97))));
    }

    @Test void editedAliasTextReplacesRemovedValuesAndCanClearTheSetWithoutChangingOtherIdentityFields() {
        var original = new CharacterIdentity(UUID.randomUUID().toString(), "Synthetic Name", "Manual note",
            Set.of("Old Alias", "Retained; Alias"), "SyntheticAccount");
        var changed = new CharacterIdentity(original.id(), original.name(), original.notes(),
            IdentityAliases.parse("Retained\\; Alias;New\\\\Alias"), original.accountObservation());
        assertEquals(Set.of("Retained; Alias", "New\\Alias"), changed.aliases());
        assertFalse(changed.aliases().contains("Old Alias")); assertEquals(original.id(), changed.id());
        assertEquals(original.name(), changed.name()); assertEquals(original.notes(), changed.notes());
        assertEquals(original.accountObservation(), changed.accountObservation());
        var cleared = new CharacterIdentity(changed.id(), changed.name(), changed.notes(), IdentityAliases.parse(""), changed.accountObservation());
        assertTrue(cleared.aliases().isEmpty());
    }
}
