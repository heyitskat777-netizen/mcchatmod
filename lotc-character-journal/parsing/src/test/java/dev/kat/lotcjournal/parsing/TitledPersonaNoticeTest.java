package dev.kat.lotcjournal.parsing;
import dev.kat.lotcjournal.domain.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TitledPersonaNoticeTest {
    @Test void commaTitledPersonaRepliesAreNoiseWhileQuotedAndPreviouslyConfirmedDialogueSurvive() {
        String notice="Synthetic Name, the Wanderer is a Persona of ExampleAccount";
        var normalizer=new DialogueTextNormalizer();
        assertEquals(DialogueCleanup.Disposition.IGNORED,normalizer.clean(notice,Channel.UNKNOWN).disposition());
        var parsed=new LogLineParser(Set.of()).parse("[12:34:56] [Render thread/INFO]: [CHAT] "+notice,1).orElseThrow();
        assertEquals(Channel.SYSTEM,parsed.channel());
        String spoken="Synthetic Speaker says: \""+notice+"\"";
        assertEquals(spoken,normalizer.clean(spoken,Channel.UNKNOWN).text());
        assertEquals(notice,normalizer.clean(notice,Channel.ROLEPLAY).text());
    }
}
