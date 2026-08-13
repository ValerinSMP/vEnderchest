package com.valerin.venderchest.session;

import com.valerin.venderchest.api.CloseReason;
import com.valerin.venderchest.api.ConflictType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultAuditLogTest {

    @Test
    void consoleEnabled_writesAuditEntries() {
        LogCapture capture = new LogCapture();
        VaultAuditLog audit = new VaultAuditLog(capture.logger, VaultAuditLog.Level.NORMAL, true, true);

        audit.opened(session());
        audit.conflict(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1",
                ConflictType.STALE_REVISION, 1, 2);

        assertTrue(capture.output.toString().contains("event=session_opened"));
        assertTrue(capture.output.toString().contains("event=conflict"));
    }

    @Test
    void consoleDisabled_suppressesAllAuditEntriesAndCanBeReloaded() {
        LogCapture capture = new LogCapture();
        VaultAuditLog audit = new VaultAuditLog(capture.logger, VaultAuditLog.Level.VERBOSE, true, false);
        VaultSession session = session();
        UUID id = UUID.randomUUID();

        audit.opened(session);
        audit.commit(session, 1, 2, List.of(), 3);
        audit.conflict(id, id, id, "1", ConflictType.STALE_REVISION, 1, 2);
        audit.reopenDetected(id, id, "1", id);
        audit.openRejected(id, id, "1", id);
        audit.closed(session, CloseReason.CLIENT_CLOSE);
        audit.loadDuration(session, 3);

        assertEquals("", capture.output.toString());

        audit.setConsoleEnabled(true);
        audit.opened(session);
        assertTrue(capture.output.toString().contains("event=session_opened"));
    }

    private static VaultSession session() {
        VaultSession session = new VaultSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "1", Instant.now(), VaultWriter.SINGLE_SERVER, 0);
        session.activate(1, new Object());
        return session;
    }

    private static final class LogCapture {
        private final StringBuilder output = new StringBuilder();
        private final Logger logger = Logger.getAnonymousLogger();

        private LogCapture() {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    output.append(record.getMessage()).append('\n');
                }

                @Override public void flush() {}
                @Override public void close() {}
            });
        }
    }
}
