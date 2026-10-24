package dev.kat.lotcjournal.parsing;

import dev.kat.lotcjournal.domain.Channel;
import java.time.LocalTime;
import java.util.Optional;

public record ParsedLogLine(long sourceLine, LocalTime clock, String text,
                            Channel channel, Optional<String> observedSpeaker) {}

