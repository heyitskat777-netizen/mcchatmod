package dev.kat.lotcjournal.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure, conservative evidence matching. Callers must check channel trust first. */
public final class CharacterNameMatcher {
    private CharacterNameMatcher() {}
    private static final String WORD = "[\\p{L}\\p{M}][\\p{L}\\p{M}\\p{N}'’._-]*";
    private static final String NAME = "(" + WORD + "(?: " + WORD + "){0,7}?)";
    private static final Pattern COLON = Pattern.compile("^" + NAME + ":\\s*\\S.*$", Pattern.DOTALL);
    private static final Pattern SPEECH = Pattern.compile("^" + NAME
        + " (?:says|asks|replies|whispers|shouts|yells|calls|murmurs|mutters|responds|remarks|exclaims|states|speaks|utters)\\b.*$", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ACTION = Pattern.compile("^\\*?" + NAME
        + " (?:waves|nods|smiles|grins|laughs|chuckles|sighs|frowns|shrugs|bows|steps|walks|turns|looks|glances|raises|lowers|reaches|takes|places|draws|sits|stands|kneels|leans|gestures|points|blinks|pauses|hums|tilts|shakes|folds|closes|opens|coughs|winces|offers|extends|taps|rests|stares|watches|pulls|pushes|lifts|holds|gazes)\\b.*$", Pattern.DOTALL | Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> UNNAMED = Set.of("a", "an", "the", "you", "your", "he", "his", "she", "her",
        "they", "their", "it", "its", "we", "our", "i", "my", "someone", "somebody", "something", "nobody", "everyone", "this", "that");
    public record Actor(String name, CharacterEvidenceKind kind) {}
    public static Optional<Actor> actor(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        for (var rule : java.util.List.of(SPEECH, ACTION, COLON)) {
            var match = rule.matcher(text);
            if (!match.matches()) continue;
            String name = match.group(1);
            if (name.length() > 96 || UNNAMED.contains(key(name.split(" ", 2)[0]))) return Optional.empty();
            return Optional.of(new Actor(name, rule == ACTION ? CharacterEvidenceKind.ACTION : CharacterEvidenceKind.SPEAKER));
        }
        return Optional.empty();
    }
    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
    /** Literal Unicode token boundaries: Ash matches Ash's, not Ashen or Smash. */
    public static boolean mentions(String text, String name) {
        return !occurrences(text, name).isEmpty();
    }
    public record Span(int start, int end) {}
    public static java.util.List<Span> occurrences(String text, String name) {
        if (text == null || name == null || name.isBlank()) return java.util.List.of();
        var result = new java.util.ArrayList<Span>();
        String haystack = key(text), needle = key(name);
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            int end = at + needle.length();
            if ((at == 0 || !token(haystack.codePointBefore(at)))
                && !insideJoinedName(haystack, at, true)
                && (end == haystack.length() || !token(haystack.codePointAt(end)))
                && !insideJoinedName(haystack, end, false)) result.add(new Span(at, end));
        }
        return java.util.List.copyOf(result);
    }
    private static boolean token(int point) {
        int type=Character.getType(point);
        return Character.isLetterOrDigit(point) || type == Character.NON_SPACING_MARK
            || type == Character.COMBINING_SPACING_MARK || type == Character.ENCLOSING_MARK || point == '_';
    }
    private static boolean insideJoinedName(String text,int boundary,boolean before) {
        int join=before?boundary-1:boundary;
        if(join<0 || join>=text.length() || "'’".indexOf(text.charAt(join))<0) return false;
        if(before) return join>0 && token(text.codePointBefore(join));
        int after=join+1;
        if(after>=text.length() || !token(text.codePointAt(after))) return false;
        // A possessive is a boundary; an apostrophe inside another name is not.
        return !("'’".indexOf(text.charAt(join))>=0 && text.charAt(after)=='s'
            && (after+1==text.length() || !token(text.codePointAt(after+1))));
    }
}
