package dev.kat.lotcjournal.domain;

public interface SettingsRepository {
    void initialize() throws Exception;
    JournalSettings load() throws Exception;
    void save(JournalSettings settings) throws Exception;
}

