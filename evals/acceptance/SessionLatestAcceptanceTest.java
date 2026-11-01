package com.thoughtcoding.eval;
import com.thoughtcoding.service.SessionService;
import com.thoughtcoding.model.ChatMessage;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionLatestAcceptanceTest {
    @Test void latestTracksModificationRatherThanFilenameOrInsertion() throws Exception {
        var service = new SessionService();
        // Exercise every choice, so accidental HashSet iteration cannot pass.
        var ids = new ArrayList<String>();
        String prefix = "eval-" + UUID.randomUUID() + "-";
        try {
            for (int i=0;i<4;i++) {
                String id=prefix+i; ids.add(id);
                service.saveSession(id, List.of(new ChatMessage("user","hello")));
            }
            for (String newest : ids) {
                for (String id : ids) Files.setLastModifiedTime(Path.of("sessions",id+".json"),
                    FileTime.fromMillis(id.equals(newest) ? 4_000_000_000_000L : 1_000L));
                assertEquals(newest, service.getLatestSessionId());
            }
        } finally { for (String id : ids) service.deleteSession(id); }
    }
    @Test void savedContentCanStillBeLoadedAndDeleted() {
        String id="eval-"+UUID.randomUUID(); var service=new SessionService();
        try {
            service.saveSession(id,List.of(new ChatMessage("user","保留内容")));
            assertEquals("保留内容", new SessionService().loadSession(id).get(0).getContent());
        } finally { assertTrue(service.deleteSession(id)); }
    }
}
