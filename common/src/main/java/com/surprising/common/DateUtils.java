package com.surprising.common;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;


/**
 * @author lilaizhen
 */
public final class DateUtils {

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd";

    private static String getDefaultDatePattern() {
        return DEFAULT_DATE_PATTERN;
    }

    public static String getDayFromToday(Integer number) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(new Date());
            c.add(Calendar.DAY_OF_YEAR, -number);
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(DEFAULT_DATE_PATTERN);
            return simpleDateFormat.format(c.getTime());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static String getToday() {
        Date today = new Date();
        return format(today);
    }

    public static String format(Date date) {
        if (date != null) {
            return format(date, getDefaultDatePattern());
        }
        return "";
    }


    public static String format(Date date, String pattern) {
        if (date != null) {
            return new SimpleDateFormat(pattern).format(date);
        }
        return "";
    }

    public static Date LocalDateTimeToDate(LocalDateTime localDateTime) {
        ZoneId zone = ZoneId.systemDefault();
        Instant instant = localDateTime.atZone(zone).toInstant();
        return Date.from(instant);
    }

}