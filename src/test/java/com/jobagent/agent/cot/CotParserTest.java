package com.jobagent.agent.cot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobagent.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CotParserTest {

    private final CotParser parser = new CotParser(new ObjectMapper());

    @Test
    void parseToolCall() {
        String raw = "{\"thinking\":\"需要制定计划\",\"tool\":\"study_plan\",\"params\":{\"days\":30}}";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.TOOL_CALL, r.getType());
        assertEquals("study_plan", r.getTool());
        assertEquals(30, r.getParams().get("days"));
    }

    @Test
    void parseFinalAnswer() {
        String raw = "{\"thinking\":\"直接回答\",\"final_answer\":\"你好\"}";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.FINAL_ANSWER, r.getType());
        assertEquals("你好", r.getFinalAnswer());
    }

    @Test
    void parseWithMarkdownFence() {
        String raw = "```json\n{\"thinking\":\"t\",\"final_answer\":\"ok\"}\n```";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.FINAL_ANSWER, r.getType());
        assertEquals("ok", r.getFinalAnswer());
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(BizException.class, () -> parser.parse("not json"));
    }

    @Test
    void parseMissingFieldsThrows() {
        assertThrows(BizException.class, () -> parser.parse("{\"thinking\":\"x\"}"));
    }

    @Test
    void parseToolCallWithoutParams() {
        String raw = "{\"thinking\":\"t\",\"tool\":\"study_plan\"}";
        CotResult r = parser.parse(raw);
        assertEquals(CotResult.Type.TOOL_CALL, r.getType());
        assertTrue(r.getParams().isEmpty());
    }
}
