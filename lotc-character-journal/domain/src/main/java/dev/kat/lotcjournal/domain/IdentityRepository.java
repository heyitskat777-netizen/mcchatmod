package dev.kat.lotcjournal.domain;

import java.util.List;
public interface IdentityRepository {
    List<CharacterIdentity> identities(String query) throws Exception;
    void saveIdentity(CharacterIdentity identity) throws Exception;
    List<Persona> personas() throws Exception;
    void createPersona(Persona persona) throws Exception;
    String activePersona() throws Exception;
    void selectPersona(String personaId) throws Exception;
    MemoryAssignment assignment(MessageReference message, String personaId) throws Exception;
    void assign(MemoryAssignment assignment) throws Exception;
    void clearAssignment(MessageReference message, String personaId) throws Exception;
    TranscriptPage memoryTranscript(String personaId, String query, Channel channel, boolean favoritesOnly, int offset, int limit) throws Exception;
    default TranscriptPage characterTranscript(String identityId, String query, Channel channel, CharacterEvidenceKind kind,
                                              boolean favoritesOnly, int offset, int limit) throws Exception {
        throw new UnsupportedOperationException("Character evidence is unavailable");
    }
    List<IdentityMerge> merges() throws Exception;
    String mergeIdentity(String fromIdentity, String toIdentity) throws Exception;
    void undoMerge(String decisionId) throws Exception;
    String resolveIdentity(String identityId) throws Exception;
    default List<IdentitySeparation> separations() throws Exception { return List.of(); }
    default String denyIdentityLink(String firstIdentity, String secondIdentity) throws Exception {
        throw new UnsupportedOperationException("Identity denials are unavailable");
    }
    default void undoSeparation(String decisionId) throws Exception {
        throw new UnsupportedOperationException("Identity denials are unavailable");
    }
}
