/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.monitoring.metrics;

/**
 * Author: Rossella Sblendido rossella@midokura.com
 * Date: 4/27/12
 */
public class MetricRegistryFillerItem {

    private String key;
    private String mBean;
    private String attribute;

    public String getKey() {
        return key;
    }

    public MetricRegistryFillerItem(String key, String attribute, String mBean) {
        this.key = key;
        this.attribute = attribute;
        this.mBean = mBean;

    }

    public String getmBean() {
        return mBean;
    }

    public String getAttribute() {
        return attribute;
    }

    public String getType() {
        // TODO(rossella) for now we will consider just string metrics. Let's see in future
        return "String";
    }

}
