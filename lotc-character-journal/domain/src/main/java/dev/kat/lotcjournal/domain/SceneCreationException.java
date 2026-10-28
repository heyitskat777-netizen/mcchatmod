package dev.kat.lotcjournal.domain;
/** Safe UI-readable validation reason; contains no private source or exception payload. */
public final class SceneCreationException extends IllegalArgumentException {
    public enum Reason { MINIMUM, TOO_LARGE }
    private final Reason reason;
    public SceneCreationException(Reason reason){super(reason==Reason.MINIMUM?"Scene creation needs 10 roleplay messages and 2 distinct actors":"Scene creation exceeds the 5000-message limit");this.reason=java.util.Objects.requireNonNull(reason);}
    public Reason reason(){return reason;}
}
