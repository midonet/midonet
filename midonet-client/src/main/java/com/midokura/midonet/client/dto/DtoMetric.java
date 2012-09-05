/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

/**
 * DtoMetric represent a query to the monitoring system.
 * Date: 5/24/12
 */

@XmlRootElement
public class DtoMetric {

    /**
     * the name of the metric
     */
    String name;
    /**
     * id of the object for which we are collecting the metric
     */
    UUID targetIdentifier;

    /**
-     * the metric type
 -    */
    String type;

    public String getName() {
        return name;
    }


    public UUID getTargetIdentifier() {
        return targetIdentifier;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setTargetIdentifier(UUID targetIdentifier) {
        this.targetIdentifier = targetIdentifier;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "DtoMetric{" +
                "targetIdentifier=" + targetIdentifier.toString() +
                "metricType=" + type +
                "metricName=" + name +
                "}";
    }
}
