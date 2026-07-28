package com.valerin.venderchest.utils;

public final class RelativeTime {

    private RelativeTime() {}

    /** "hace 3h" style, only ever the single largest unit. */
    public static String since(long millis) {
        long diffSeconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (diffSeconds < 60) return "hace " + diffSeconds + "s";
        long minutes = diffSeconds / 60;
        if (minutes < 60) return "hace " + minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return "hace " + hours + "h";
        return "hace " + (hours / 24) + "d";
    }
}
