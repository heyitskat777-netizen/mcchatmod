package dev.kat.lotcjournal.domain;

/** Pure injected transport cleanup/privacy policy, shared by imports and schema migration. */
@FunctionalInterface
public interface DialogueNormalizer {
    DialogueCleanup clean(String text, Channel previousChannel);
}
