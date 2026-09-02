package com.rpa.server.service;

/** Pure gray-release matching rules, shared by publish/resolve logic. */
public final class GrayRule {
    private GrayRule() {}

    public static boolean matches(String deviceSn, Long groupId, String targetType, String targetValue) {
        if (targetType == null) return false;
        switch (targetType) {
            case "ALL":
                return true;
            case "GROUP":
                if (groupId == null || targetValue == null) return false;
                for (String s : targetValue.split(",")) {
                    if (String.valueOf(groupId).equals(s.trim())) return true;
                }
                return false;
            case "PERCENT":
                int pct;
                try {
                    pct = Integer.parseInt(String.valueOf(targetValue).trim());
                } catch (NumberFormatException e) {
                    return false;
                }
                return percentHit(deviceSn, pct);
            default:
                return false;
        }
    }

    public static boolean percentHit(String deviceSn, int percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        return Math.floorMod(deviceSn.hashCode(), 100) < percent;
    }
}
