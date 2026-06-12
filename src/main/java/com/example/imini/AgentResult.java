package com.example.imini;

import java.util.List;
import java.util.Map;

/**
 * What a conversation turn produced: the text answer plus the full, possibly compacted, message
 * history so the caller can persist it for a multi-turn session.
 */
public record AgentResult(String answer, List<Map<String, Object>> messages) {}
