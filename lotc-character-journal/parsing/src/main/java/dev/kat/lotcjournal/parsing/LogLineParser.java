package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative historical-log parser. It is not connected to live capture.
 * Names must come from a reviewed observation, never from guessing the first word.
 * Clock is the original log clock; no date or timezone is invented.
 */
public final class LogLineParser {
    private static final Pattern PREFIX = Pattern.compile(
        "^\\[(\\d{2}:\\d{2}:\\d{2})] \\[[^]\\r\\n]+/INFO]: (?:\\[System] )?\\[CHAT] (.*)$");
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern CLOCK = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})]\\s*");
    private static final Pattern OOC = Pattern.compile(
        "(?i)^(?:\\[[^]\\r\\n]{1,80}]\\s*){0,3}\\[(?:LOOC|L[-_ ]OOC|LOCAL[-_ ]OOC|OOC|G|GLOBAL|W|WORLD|HELP|WS|STAFF)]");
    private static final Pattern PM = Pattern.compile(
        "(?i)^(?:(?:\\[[^]\\r\\n]{1,80}]\\s*){0,3}\\[(?:PM|MSG|MESSAGE|WHISPER|TELL|DM|PRIVATE)(?:[ :][^]\\r\\n]*)?]"
            + "|\\[[^]\\r\\n]+\\s*(?:->|→)\\s*[^]\\r\\n]+]"
            + "|(?:from|to)\\s+[^:\\r\\n]+:"
            + "|[^:\\[\\]\\r\\n]{1,128}\\s*(?:->|→)\\s*[^:\\[\\]\\r\\n]{1,128}:)");
    private final java.util.List<String> speakers;

    public LogLineParser(Set<String> reviewedObservedNames) {
        speakers = reviewedObservedNames.stream().filter(s -> !s.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    public Optional<ParsedLogLine> parse(String raw, long lineNumber) {
        if (lineNumber < 1) throw new IllegalArgumentException("Source lines start at 1");
        var match = PREFIX.matcher(raw);
        if (!match.matches()) return Optional.empty();
        final LocalTime clock;
        try { clock = LocalTime.parse(match.group(1)); }
        catch (DateTimeParseException invalid) { return Optional.empty(); }
        String text = ANSI.matcher(COLOR.matcher(match.group(2)).replaceAll("")).replaceAll("").stripLeading();
        var chatClock = CLOCK.matcher(text);
        if (chatClock.find()) {
            try {
                LocalTime.parse(chatClock.group(1));
                text = text.substring(chatClock.end());
            } catch (DateTimeParseException invalidChatClock) {
                // Preserve the malformed clock as unresolved text, rather than turning
                // the remaining payload into an apparently understood RP observation.
            }
        }
        Channel channel = Channel.UNKNOWN;
        Optional<String> speaker = Optional.empty();
        if (PM.matcher(text).find()) channel = Channel.PRIVATE_MESSAGE;
        else if (OOC.matcher(text).find() || text.startsWith("((")) channel = Channel.OOC;
        else if (text.startsWith("[Replay Mod]") || text.startsWith("[Aviary]")) channel = Channel.SYSTEM;
        else if (!text.startsWith("[") && !text.startsWith("(")) {
            String observedText = text;
            speaker = speakers.stream().filter(name -> observedText.startsWith(name + " ")
                || observedText.startsWith(name + ":")).findFirst();
            if (speaker.isPresent()) channel = Channel.ROLEPLAY;
        }
        return Optional.of(new ParsedLogLine(lineNumber, clock, text, channel, speaker));
    }
}

