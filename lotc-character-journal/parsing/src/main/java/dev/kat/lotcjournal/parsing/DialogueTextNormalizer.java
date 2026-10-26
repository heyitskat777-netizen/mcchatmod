package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import dev.kat.lotcjournal.domain.DialogueCleanup;
import dev.kat.lotcjournal.domain.DialogueNormalizer;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Anchored transport cleanup. Unknown tags and markers inside speech remain observations. */
public final class DialogueTextNormalizer implements DialogueNormalizer {
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern TRANSPORT = Pattern.compile(
        "^\\[\\d{2}:\\d{2}:\\d{2}] \\[[^]\\r\\n]+/INFO]:\\s*(?:\\[System]\\s*)?\\[CHAT]\\s*");
    private static final Pattern CLOCK = Pattern.compile("\\[(\\d{2}:\\d{2}:\\d{2})]\\s*");
    private static final Pattern RP = Pattern.compile("(?i)\\[(?:Quiet|Shout|Local|RP|Roleplay|Normal)]\\s*");
    private static final Pattern SENDER = Pattern.compile(
        "(?s)(?:[\\p{L}\\p{N}_'’.-][\\p{L}\\p{N}_'’ .-]{0,95}:\\s*\\S.*"
            + "|[\\p{Lu}][\\p{L}\\p{N}_'’.-]*[ \\t]+\\S[^\\r\\n]*"
            + "|(?:\\[!]|\\*{1,2})\\s*\\S.+)");
    private static final Pattern OOC = Pattern.compile(
        "(?i)^(?:\\[[^]\\r\\n]{1,80}]\\s*){0,3}\\[(?:LOOC|L[-_ ]OOC|LOCAL[-_ ]OOC|OOC|G|GLOBAL|W|WORLD|HELP|WS|STAFF)]");
    private static final Pattern PM = Pattern.compile(
        "(?i)^(?:(?:\\[[^]\\r\\n]{1,80}]\\s*){0,3}\\[(?:PM|MSG|MESSAGE|WHISPER|TELL|DM|PRIVATE)(?:[ :][^]\\r\\n]*)?]"
            + "|\\[[^]\\r\\n]+\\s*(?:->|→)\\s*[^]\\r\\n]+]"
            + "|(?:from|to)\\s+[^:\\r\\n]+:"
            + "|[^:\\[\\]\\r\\n]{1,128}\\s*(?:->|→)\\s*[^:\\[\\]\\r\\n]{1,128}:)");

    String formatted(String input) {
        String text = ANSI.matcher(COLOR.matcher(input).replaceAll("")).replaceAll("").stripLeading();
        var transport = TRANSPORT.matcher(text);
        return transport.find() ? text.substring(transport.end()) : text;
    }

    String payload(String input) {
        String text = formatted(input);
        int cursor = 0;
        while (cursor < text.length()) {
            var clock = CLOCK.matcher(text).region(cursor, text.length());
            if (clock.lookingAt()) {
                try { LocalTime.parse(clock.group(1)); cursor = clock.end(); continue; }
                catch (DateTimeParseException malformed) { break; }
            }
            var channel = RP.matcher(text).region(cursor, text.length());
            if (!channel.lookingAt()) break;
            cursor = channel.end();
        }
        return text.substring(cursor);
    }

    Channel classification(String input) {
        String original = formatted(input), text = payload(original);
        if (PM.matcher(original).find() || PM.matcher(text).find()) return Channel.PRIVATE_MESSAGE;
        if (OOC.matcher(original).find() || OOC.matcher(text).find() || original.startsWith("((") || text.startsWith("(("))
            return Channel.OOC;
        if (trustedRoleplayEnvelope(original) && SENDER.matcher(text).matches()) return Channel.ROLEPLAY;
        if (text.startsWith("[Replay Mod]") || text.startsWith("[Aviary]")) return Channel.SYSTEM;
        if (SystemNoticeClassifier.matches(text)) return Channel.SYSTEM;
        return Channel.UNKNOWN;
    }

    /** The original channel proof is needed; stripped text alone cannot supply it during migration. */
    private boolean trustedRoleplayEnvelope(String original) {
        int cursor = 0;
        boolean trusted = false;
        while (cursor < original.length()) {
            var clock = CLOCK.matcher(original).region(cursor, original.length());
            if (clock.lookingAt()) {
                try { LocalTime.parse(clock.group(1)); cursor = clock.end(); continue; }
                catch (DateTimeParseException malformed) { return false; }
            }
            var roleplay = RP.matcher(original).region(cursor, original.length());
            if (!roleplay.lookingAt()) break;
            trusted = true;
            cursor = roleplay.end();
        }
        return trusted;
    }

    @Override public DialogueCleanup clean(String text, Channel previousChannel) {
        var channel = classification(text);
        if (previousChannel == Channel.PRIVATE_MESSAGE || previousChannel == Channel.OOC
            || channel == Channel.PRIVATE_MESSAGE || channel == Channel.OOC)
            return new DialogueCleanup(null, DialogueCleanup.Disposition.EXCLUDED);
        String dialogue = payload(text);
        if (previousChannel == Channel.SYSTEM || (channel == Channel.SYSTEM && previousChannel != Channel.ROLEPLAY) || dialogue.isBlank())
            return new DialogueCleanup(null, DialogueCleanup.Disposition.IGNORED);
        return new DialogueCleanup(dialogue, DialogueCleanup.Disposition.DIALOGUE);
    }
}
