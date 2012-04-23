/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.store;

import java.util.Map;

public interface Store {

    public void addTSPoint(String interfaceName, long time, String value,
                           String metricName, String granularity);

    public String getTSPoint(String interfaceName, long time, String metricName,
                             String granularity);

    public Map<String, String> getTSPoint(String interfaceName, long timeStart, long timeEnd, String metricName, String granularity);

}