package concurrency.adapter;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerCliTest {
    @Test
    void parsesOnlyKnownUniqueOptions() {
        Map<String, String> options = SchedulerCli.parse(new String[]{
                "--plan", "plan.json", "--evidence", "evidence.json", "--policy-only"
        });
        assertEquals("plan.json", options.get("--plan"));
        assertEquals("true", options.get("--policy-only"));
    }

    @Test
    void rejectsUnknownDuplicateAndMissingValueWithoutEchoingValues() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> SchedulerCli.parse(new String[]{"private-input"}));
        assertEquals("unknown CLI option", unknown.getMessage());

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> SchedulerCli.parse(new String[]{"--plan", "first.json", "--plan", "second.json"}));
        assertEquals("duplicate CLI option: --plan", duplicate.getMessage());
        assertFalse(duplicate.getMessage().contains("second.json"));

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> SchedulerCli.parse(new String[]{"--plan", "--evidence", "evidence.json"}));
        assertEquals("missing value for CLI option: --plan", missing.getMessage());
    }

    @Test
    void createsOnlyANewJournalAndPreservesExistingContent() throws Exception {
        Path directory = Files.createTempDirectory("scheduler-journal-");
        Path journal = directory.resolve("events.jsonl");
        SchedulerCli.prepareEventJournal(journal);
        assertTrue(Files.isRegularFile(journal));
        Files.write(journal, "preserve-me\n".getBytes("UTF-8"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SchedulerCli.prepareEventJournal(journal));
        assertEquals("events output must be a new path", failure.getMessage());
        assertEquals("preserve-me\n", new String(Files.readAllBytes(journal), "UTF-8"));
    }

    @Test
    void rejectsSymlinkJournalWithoutChangingTarget() throws Exception {
        Path directory = Files.createTempDirectory("scheduler-journal-link-");
        Path target = directory.resolve("existing-events.jsonl");
        Files.write(target, "preserve-target\n".getBytes("UTF-8"));
        Path link = directory.resolve("events.jsonl");
        Files.createSymbolicLink(link, target.getFileName());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SchedulerCli.prepareEventJournal(link));
        assertEquals("events output must be a new path", failure.getMessage());
        assertEquals("preserve-target\n", new String(Files.readAllBytes(target), "UTF-8"));
    }
}
