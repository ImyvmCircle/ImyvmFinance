package com.imyvm.finance.quote;

import com.imyvm.finance.market.MarketStatus;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class MarketHours {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private MarketHours() {
    }

    public static MarketStatus status(String market, Instant instant) {
        if ("CRYPTO".equals(market))
            return MarketStatus.OPEN;
        ZoneId zone = switch (market) {
            case "CN" -> SHANGHAI;
            case "HK" -> HONG_KONG;
            case "US" -> NEW_YORK;
            default -> null;
        };
        if (zone == null)
            return MarketStatus.UNAVAILABLE;
        ZonedDateTime local = instant.atZone(zone);
        if (local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY)
            return MarketStatus.CLOSED;
        LocalTime time = local.toLocalTime();
        boolean open = switch (market) {
            case "CN" -> inSession(time, LocalTime.of(9, 30), LocalTime.NOON)
                || inSession(time, LocalTime.of(13, 0), LocalTime.of(15, 0));
            case "HK" -> inSession(time, LocalTime.of(9, 30), LocalTime.NOON)
                || inSession(time, LocalTime.of(13, 0), LocalTime.of(16, 0));
            case "US" -> inSession(time, LocalTime.of(9, 30), LocalTime.of(16, 0));
            default -> false;
        };
        return open ? MarketStatus.OPEN : MarketStatus.CLOSED;
    }

    private static boolean inSession(LocalTime time, LocalTime start, LocalTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}
