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
        "^\\[(\\d{2}:\\d{2}:\\d{2})] \\[[^]\\r\\n]+/INFO]: (?:\\[System]\\s*)?\\[CHAT]\\s*(.*)$");
    private final DialogueTextNormalizer normalizer = new DialogueTextNormalizer();
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
        String text = normalizer.payload(match.group(2));
        if (text.isBlank()) return Optional.empty();
        Channel channel = normalizer.classification(match.group(2));
        Optional<String> speaker = Optional.empty();
        if (channel == Channel.UNKNOWN && !text.startsWith("[") && !text.startsWith("(")) {
            String observedText = text;
            speaker = speakers.stream().filter(name -> observedText.startsWith(name + " ")
                || observedText.startsWith(name + ":")).findFirst();
            if (speaker.isPresent()) channel = Channel.ROLEPLAY;
        }
        return Optional.of(new ParsedLogLine(lineNumber, clock, text, channel, speaker));
    }
}

