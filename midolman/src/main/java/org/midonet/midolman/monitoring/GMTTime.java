/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.monitoring;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;

public class GMTTime {

    public static String getDayMonthYear(long time) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"));
        cal.setTimeInMillis(time);
        SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy");
        return format.format(cal.getTime());
    }

    public static int getNumberOfDays(long timeStart, long timeEnd) {
        long diff = timeEnd - timeStart;
        return (int) diff / (24 * 60 * 60 * 1000);
    }
}
