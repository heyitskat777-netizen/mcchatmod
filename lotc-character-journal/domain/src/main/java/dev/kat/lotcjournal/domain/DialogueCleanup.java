package dev.kat.lotcjournal.domain;

import java.util.Objects;

/** Excluded/ignored results must not retain their payload. Attribution never changes here. */
public record DialogueCleanup(String text, Disposition disposition) {
    public enum Disposition { DIALOGUE, EXCLUDED, IGNORED }
    public DialogueCleanup {
        Objects.requireNonNull(disposition, "disposition");
        if (disposition == Disposition.DIALOGUE) {
            if (text == null || text.isBlank() || text.length() > 65_536)
                throw new IllegalArgumentException("Invalid dialogue payload");
        } else if (text != null) throw new IllegalArgumentException("Discarded payload must not be retained");
    }
}
