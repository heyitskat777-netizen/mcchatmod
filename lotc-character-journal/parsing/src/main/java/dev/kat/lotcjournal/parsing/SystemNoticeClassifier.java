package dev.kat.lotcjournal.parsing;

import java.util.List;
import java.util.regex.Pattern;

/** Whole, unattributed server templates only. Never use substring/keyword filtering on dialogue. */
final class SystemNoticeClassifier {
    private static final String PERSONA = "[\\p{L}\\p{N}_'’ -]{1,96}";
    private static final String ACCOUNT = "[A-Za-z0-9_]{1,16}";
    private static final List<Pattern> NOTICES = List.of(
        notice("(?:\\[Personas?]\\s*)?You are now Roleplaying as " + PERSONA + "[.!]?"),
        notice("(?:Your persona['’]s (?:size|height) is(?: currently)?|Your (?:current )?(?:size|height) is|Persona (?:size|height):)\\s*\\d+(?:\\.\\d+)?(?:\\s*(?:blocks?|meters?|m)(?: tall)?)?[.!]?"),
        notice("Set size for persona " + PERSONA + " to: [0-9]+(?:\\.[0-9]+)? \\([0-9]+(?:\\.[0-9]+)?cm, [0-9]+(?:\\.[0-9]+)?in, [0-9]+'[0-9]+\"\\)"),
        notice("JourneyMap: Press \\[[^]\\r\\n]{1,24}]"),
        notice("(?:\\[JourneyMap]\\s*:?|JourneyMap:)\\s*Press (?:\\[[^]\\r\\n]{1,24}]|[A-Z0-9+_-]{1,24}) to open (?:the )?(?:map|JourneyMap|full[- ]screen map)[.!]?"),
        notice(ACCOUNT + " (?:has sent|sent) you a friend request[.!]?"),
        notice("You (?:have received|received) a friend request from " + ACCOUNT + "[.!]?"),
        notice("You have (?:a|[0-9]+) pending friend requests?(?: from " + ACCOUNT + ")?[.!]?"),
        notice("(?:\\[News]|(?:Latest |Server )?News:|[-=]{3,}\\s*(?:Latest |Server )?News\\s*[-=]{3,})"),
        notice("(?:•|\\.)[ \\t]+\\[Link][ \\t]+[^\\r\\n]+"),
        notice("Your soulstone is (?:still )?on cooldown(?: for [0-9]+(?:\\.[0-9]+)? (?:seconds?|minutes?))?[.!]?"),
        notice("Your soulstone will recharge in [0-9]+(?:\\.[0-9]+)? (?:seconds?|minutes?)[.!]?"),
        notice("Your soulstone (?:begins to glow(?: faintly)?|glows(?: faintly)?|glows with a faint light|has recharged|has finished recharging)[.!]?"),
        notice("Persona search results(?: for " + PERSONA + ")?:?"),
        notice("Showing matches to Persona name " + PERSONA + "[.!]?"),
        notice(PERSONA + " is a Persona of " + ACCOUNT + "[.!]?"),
        notice("The key did not fit!"),
        notice("Can you build\\? (?:Yes|No)[.!]?")
    );
    private static Pattern notice(String expression) {
        return Pattern.compile("^(?:" + expression + ")$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
    static boolean matches(String text) {
        return regionNotice(text) || friendRequestList(text)
            || NOTICES.stream().anyMatch(pattern -> pattern.matcher(text).matches());
    }

    // A regex quantified group consumes JVM stack once per item. Real log lines may
    // contain thousands of IDs, so parse these lists iteratively with constant stack.
    private static boolean regionNotice(String text) {
        String prefix = "Applicable regions:";
        if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        int start = prefix.length(), end = text.length();
        while (start < end && horizontal(text.charAt(start))) start++;
        if (text.substring(start).equalsIgnoreCase("(none)")) return true;
        if (start < end && text.charAt(start) == '[') {
            if (text.charAt(end - 1) != ']') return false;
            start++; end--;
            if (start == end) return true;
        }
        while (start < end) {
            int token = start;
            while (start < end && regionCharacter(text.charAt(start))) start++;
            if (start == token) return false;
            if (start == end) return true;
            while (start < end && horizontal(text.charAt(start))) start++;
            if (start == end || text.charAt(start++) != ',') return false;
            while (start < end && horizontal(text.charAt(start))) start++;
            if (start == end) return false;
        }
        return false;
    }

    private static boolean friendRequestList(String text) {
        String prefix = "You have friend requests from: ";
        if (!text.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        int cursor = prefix.length(), end = text.length();
        if (end > cursor && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == '!')) end--;
        while (cursor < end) {
            int token = cursor;
            while (cursor < end && accountCharacter(text.charAt(cursor))) cursor++;
            if (cursor == token || cursor - token > 16) return false;
            if (cursor == end) return true;
            if (cursor + 2 > end || text.charAt(cursor) != ',' || text.charAt(cursor + 1) != ' ') return false;
            cursor += 2;
            if (cursor == end) return false;
        }
        return false;
    }

    private static boolean horizontal(char value) { return value == ' ' || value == '\t'; }
    private static boolean accountCharacter(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
            || value >= '0' && value <= '9' || value == '_';
    }
    private static boolean regionCharacter(char value) {
        return accountCharacter(value) || value == '.' || value == ':' || value == '-';
    }
    private SystemNoticeClassifier() {}
}
