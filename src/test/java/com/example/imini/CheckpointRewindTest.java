package com.example.imini;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Group-aware rewind: a batch undoes all its files at once; single edits stay independent. */
class CheckpointRewindTest {

    // Database not initialized -> available()=false -> CheckpointStore uses its in-memory fallback.
    private CheckpointStore store() {
        return new CheckpointStore(new Database());
    }

    @Test
    void singleEditRewindsOneFile() throws Exception {
        CheckpointStore cs = store();
        Path f = Files.createTempFile("ckpt", ".txt");
        Files.writeString(f, "v1");
        cs.snapshot(f);
        Files.writeString(f, "v2");
        String msg = cs.rewindLast("default");
        assertEquals("v1", Files.readString(f));
        assertTrue(msg.startsWith("Rewound the last change ("), msg);
    }

    @Test
    void batchRewindsAllFilesInOneCall() throws Exception {
        CheckpointStore cs = store();
        Path a = Files.createTempFile("a", ".txt");
        Path b = Files.createTempFile("b", ".txt");
        Files.writeString(a, "A1");
        Files.writeString(b, "B1");
        cs.beginBatch();
        cs.snapshot(a);
        cs.snapshot(b);
        cs.endBatch();
        Files.writeString(a, "A2");
        Files.writeString(b, "B2");

        String msg = cs.rewindLast("default");
        assertEquals("A1", Files.readString(a));
        assertEquals("B1", Files.readString(b));
        assertTrue(msg.contains("2 file(s)"), msg);
        assertTrue(cs.rewindLast("default").startsWith("Nothing to rewind"));
    }

    @Test
    void separateEditsRewindIndependently() throws Exception {
        CheckpointStore cs = store();
        Path a = Files.createTempFile("a", ".txt");
        Path b = Files.createTempFile("b", ".txt");
        Files.writeString(a, "A1");
        Files.writeString(b, "B1");
        cs.snapshot(a);
        Files.writeString(a, "A2");
        cs.snapshot(b);
        Files.writeString(b, "B2");

        cs.rewindLast("default");                 // undoes only the most recent (b)
        assertEquals("B1", Files.readString(b));
        assertEquals("A2", Files.readString(a));  // a untouched until its own rewind
        cs.rewindLast("default");
        assertEquals("A1", Files.readString(a));
    }
}
