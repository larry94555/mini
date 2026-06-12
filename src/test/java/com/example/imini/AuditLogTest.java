package com.example.imini;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.example.imini.AuditLog.Entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit filter: by user (case-insensitive), by target substring, combined, and capped. */
class AuditLogTest {

    private List<Entry> sample() {
        List<Entry> es = new ArrayList<>();
        es.add(new Entry("1", 3, "t3", "bob", "rewind", "session:proj", "ok"));
        es.add(new Entry("2", 2, "t2", "alice", "approve", "approval:x session:proj", "resolved allow"));
        es.add(new Entry("3", 1, "t1", "bob", "chat", "session:other", "started"));
        return es;
    }

    @Test
    void filtersByUserCaseInsensitive() {
        assertEquals(2, AuditLog.filter(sample(), "bob", "", 100).size());
        assertEquals(2, AuditLog.filter(sample(), "BOB", "", 100).size());
        assertEquals(1, AuditLog.filter(sample(), "alice", "", 100).size());
    }

    @Test
    void filtersByTargetSubstring() {
        assertEquals(2, AuditLog.filter(sample(), "", "proj", 100).size());
        assertEquals(1, AuditLog.filter(sample(), "", "other", 100).size());
        assertEquals(1, AuditLog.filter(sample(), "", "approval:x", 100).size());
    }

    @Test
    void combinesUserAndTarget() {
        List<Entry> r = AuditLog.filter(sample(), "bob", "proj", 100);
        assertEquals(1, r.size());
        assertEquals("rewind", r.get(0).action());
    }

    @Test
    void respectsLimitAndEmptyFilters() {
        assertEquals(1, AuditLog.filter(sample(), "", "", 1).size());
        assertEquals(3, AuditLog.filter(sample(), "", "", 100).size());
        assertEquals(3, AuditLog.filter(sample(), "", "", 0).size()); // 0 -> default cap (100)
    }

    @Test
    void preservesInputOrderNewestFirst() {
        List<Entry> r = AuditLog.filter(sample(), "", "", 100);
        assertTrue(r.get(0).ts() >= r.get(1).ts() && r.get(1).ts() >= r.get(2).ts());
    }
}
