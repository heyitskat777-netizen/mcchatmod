package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Conservative Phase 2 prototype, not connected to live capture yet.
 * Names must come from a reviewed observation, never from guessing the first word.
 * Clock is the original log clock; no date or timezone is invented.
 */
public final class LogLineParser {
    private static final Pattern PREFIX = Pattern.compile(
        "^\\[(\\d{2}:\\d{2}:\\d{2})] \\[[^]\\r\\n]+/INFO]: (?:\\[System] )?\\[CHAT] (.*)$");
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final Pattern CLOCK = Pattern.compile("^\\[\\d{2}:\\d{2}:\\d{2}]\\s*");
    private static final Pattern OOC = Pattern.compile(
        "(?i)^\\[(?:LOOC|OOC|G|GLOBAL|W|WORLD|HELP|WS|STAFF)](?:\\s|$)");
    private static final Pattern PM = Pattern.compile(
        "(?i)^(?:\\[[^]\\r\\n]+\\s*(?:->|→)\\s*[^]\\r\\n]+]|(?:from|to)\\s+[^:]+:)");
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
        String text = CLOCK.matcher(COLOR.matcher(match.group(2)).replaceAll("")).replaceFirst("");
        Channel channel = Channel.UNKNOWN;
        Optional<String> speaker = Optional.empty();
        if (PM.matcher(text).find()) channel = Channel.PRIVATE_MESSAGE;
        else if (OOC.matcher(text).find() || text.startsWith("((")) channel = Channel.OOC;
        else if (text.startsWith("[Replay Mod]") || text.startsWith("[Aviary]")) channel = Channel.SYSTEM;
        else if (!text.startsWith("[") && !text.startsWith("(")) {
            speaker = speakers.stream().filter(name -> text.startsWith(name + " ")
                || text.startsWith(name + ":")).findFirst();
            if (speaker.isPresent()) channel = Channel.ROLEPLAY;
        }
        return Optional.of(new ParsedLogLine(lineNumber, clock, text, channel, speaker));
    }
}

