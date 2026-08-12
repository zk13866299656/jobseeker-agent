package com.jobagent.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void ok() {
        Result<String> r = Result.ok("data");
        assertEquals(0, r.getCode());
        assertEquals("success", r.getMessage());
        assertEquals("data", r.getData());
    }

    @Test
    void error() {
        Result<Void> r = Result.error(500, "失败");
        assertEquals(500, r.getCode());
        assertEquals("失败", r.getMessage());
    }
}
