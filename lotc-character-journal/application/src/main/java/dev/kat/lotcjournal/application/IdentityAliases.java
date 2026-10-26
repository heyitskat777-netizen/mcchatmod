package dev.kat.lotcjournal.application;

import java.util.LinkedHashSet;
import java.util.Set;

/** Lossless bounded alias form syntax: semicolon separators, escaped semicolons/backslashes. */
public final class IdentityAliases {
    public static final int MAX_TEXT = 40000;
    private IdentityAliases() {}
    public static String format(Set<String> aliases) {
        validate(aliases);
        return aliases.stream().sorted().map(alias -> alias.replace("\\", "\\\\").replace(";", "\\;"))
            .collect(java.util.stream.Collectors.joining(";"));
    }
    public static Set<String> parse(String input) {
        if (input == null || input.length() > MAX_TEXT) throw new IllegalArgumentException("Alias input is too long");
        if (input.isEmpty()) return Set.of();
        var values = new LinkedHashSet<String>();
        var current = new StringBuilder();
        boolean escape = false;
        for (int index = 0; index < input.length(); index++) {
            char value = input.charAt(index);
            if (escape) {
                if (value != ';' && value != '\\') throw new IllegalArgumentException("Invalid alias escape");
                current.append(value); escape = false;
            } else if (value == '\\') escape = true;
            else if (value == ';') { add(values, current); current.setLength(0); }
            else current.append(value);
            if (current.length() > 96) throw new IllegalArgumentException("Alias is too long");
        }
        if (escape) throw new IllegalArgumentException("Incomplete alias escape");
        add(values, current);
        return Set.copyOf(values);
    }
    private static void add(Set<String> values, StringBuilder value) {
        if (value.toString().isBlank()) throw new IllegalArgumentException("Empty alias");
        values.add(value.toString()); validate(values);
    }
    private static void validate(Set<String> values) {
        if (values == null || values.size() > 200 || values.stream().anyMatch(a -> a == null || a.isBlank() || a.length() > 96))
            throw new IllegalArgumentException("Invalid aliases");
    }
}
