package com.rpa.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrayRuleTest {

    @Test
    void allTargetAlwaysMatches() {
        assertTrue(GrayRule.matches("SN-1", null, "ALL", null));
        assertTrue(GrayRule.matches("SN-1", 5L, "ALL", ""));
    }

    @Test
    void groupTargetMatchesMembership() {
        assertTrue(GrayRule.matches("SN-1", 3L, "GROUP", "3"));
        assertTrue(GrayRule.matches("SN-1", 3L, "GROUP", "1, 3,5"));
        assertFalse(GrayRule.matches("SN-1", 3L, "GROUP", "1,2"));
        assertFalse(GrayRule.matches("SN-1", null, "GROUP", "3"));
        assertFalse(GrayRule.matches("SN-1", 3L, "GROUP", null));
    }

    @Test
    void percentBoundaries() {
        assertTrue(GrayRule.percentHit("SN-1", 100));
        assertFalse(GrayRule.percentHit("SN-1", 0));
        assertFalse(GrayRule.percentHit("SN-1", -5));
    }

    @Test
    void percentIsStableAndApproximatelyUniform() {
        int hits = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            String sn = "SN-" + i;
            boolean first = GrayRule.percentHit(sn, 30);
            boolean second = GrayRule.percentHit(sn, 30);
            assertTrue(first == second, "percent hit must be stable for same sn");
            if (first) hits++;
        }
        // 30% gray: 1000 devices should hit between 20% and 40%
        assertTrue(hits > total * 0.2 && hits < total * 0.4, "hits=" + hits);
    }

    @Test
    void unknownTargetNeverMatches() {
        assertFalse(GrayRule.matches("SN-1", 1L, "WHAT", "1"));
        assertFalse(GrayRule.matches("SN-1", 1L, null, "1"));
        assertFalse(GrayRule.matches("SN-1", 1L, "PERCENT", "abc"));
    }
}
