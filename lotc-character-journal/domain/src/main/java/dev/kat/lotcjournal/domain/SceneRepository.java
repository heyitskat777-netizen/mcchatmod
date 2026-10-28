package dev.kat.lotcjournal.domain;
import java.util.List;
/** Shared scene decisions never grant persona knowledge. Non-null persona reads project every field from assigned evidence only. */
public interface SceneRepository {
    SceneSettings settings() throws Exception;
    void saveSettings(SceneSettings settings) throws Exception;
    int refreshSuggestions() throws Exception;
    ScenePage scenes(String personaId, boolean includeSkipped, String query, int offset, int limit) throws Exception;
    SceneDetail scene(String id, String personaId) throws Exception;
    TranscriptPage transcript(String id, String personaId, String query, int offset, int limit) throws Exception;
    SceneDetail saveScene(String id, long expectedRevision, String title, String location, List<SceneParticipant> participants) throws Exception;
    void skipScene(String id, long expectedRevision) throws Exception;
    List<String> splitScene(String id, long expectedRevision, MessageReference firstOfSecond) throws Exception;
    String combineScenes(String first, long firstRevision, String second, long secondRevision) throws Exception;
}
