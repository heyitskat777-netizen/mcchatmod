package dev.kat.lotcjournal.domain;
/** Gap threshold is configurable; active-time estimate caps each counted gap at one minute. */
public record SceneSettings(int inactivitySeconds) {
    public static final SceneSettings DEFAULT = new SceneSettings(900);
    public static final int ACTIVE_GAP_CAP_SECONDS = 60;
    public SceneSettings { if(inactivitySeconds<60 || inactivitySeconds>7200) throw new IllegalArgumentException("Scene gap must be 1–120 minutes"); }
}
