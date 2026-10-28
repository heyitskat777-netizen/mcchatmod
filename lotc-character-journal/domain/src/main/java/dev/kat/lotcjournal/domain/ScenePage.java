package dev.kat.lotcjournal.domain;
import java.util.List;
public record ScenePage(List<SceneSummary> entries, long total, int offset, int limit) {
    public ScenePage { entries=List.copyOf(entries); if(total<entries.size() || offset<0 || limit<1 || limit>100 || entries.size()>limit) throw new IllegalArgumentException("Invalid scene page"); }
}
