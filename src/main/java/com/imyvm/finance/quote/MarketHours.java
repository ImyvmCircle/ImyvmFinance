package com.imyvm.finance.quote;

import com.imyvm.finance.market.MarketStatus;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.LocalDate;
import java.util.Set;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class MarketHours {
    public static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static volatile ZoneId displayZone = CHINA_ZONE;

    private MarketHours() {
    }

    public static ZoneId displayZone() {
        return displayZone;
    }

    public static void setDisplayZone(ZoneId zone) {
        displayZone = zone == null ? CHINA_ZONE : zone;
    }

    public static MarketStatus status(String market, Instant instant) {
        return status(market, instant, Set.of());
    }

    public static MarketStatus status(String market, Instant instant, Set<LocalDate> holidays) {
        if ("CRYPTO".equals(market))
            return MarketStatus.OPEN;
        ZoneId zone = switch (market) {
            case "CN", "GOLD", "BOND", "FUTURES" -> CHINA_ZONE;
            case "HK" -> HONG_KONG;
            case "US" -> NEW_YORK;
            default -> null;
        };
        if (zone == null)
            return MarketStatus.UNAVAILABLE;
        ZonedDateTime local = instant.atZone(zone);
        if (holidays.contains(local.toLocalDate())
            || local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY)
            return MarketStatus.CLOSED;
        LocalTime time = local.toLocalTime();
        boolean open = switch (market) {
            case "CN", "GOLD", "BOND", "FUTURES" -> inSession(time, LocalTime.of(9, 30), LocalTime.of(11, 30))
                || inSession(time, LocalTime.of(13, 0), LocalTime.of(15, 0));
            case "HK" -> inSession(time, LocalTime.of(9, 30), LocalTime.NOON)
                || inSession(time, LocalTime.of(13, 0), LocalTime.of(16, 0));
            case "US" -> inSession(time, LocalTime.of(9, 30), LocalTime.of(16, 0));
            default -> false;
        };
        return open ? MarketStatus.OPEN : MarketStatus.CLOSED;
    }

    public static boolean withinCloseWindow(String market, Instant instant, Set<LocalDate> holidays, long minutes) {
        if (!"CN".equals(market) || minutes <= 0)
            return false;
        ZonedDateTime local = instant.atZone(CHINA_ZONE);
        if (holidays.contains(local.toLocalDate())
            || local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY)
            return false;
        LocalTime close = LocalTime.of(15, 0);
        LocalTime time = local.toLocalTime();
        return !time.isBefore(close.minusMinutes(minutes)) && time.isBefore(close);
    }

    private static boolean inSession(LocalTime time, LocalTime start, LocalTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}
