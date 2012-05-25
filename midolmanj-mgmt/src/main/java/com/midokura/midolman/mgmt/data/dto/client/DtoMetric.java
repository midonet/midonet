/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Date: 5/24/12
 */

@XmlRootElement
public class DtoMetric {

    String name;
    UUID targetIdentifier;

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

    @Override
    public String toString() {
        return "DtoMetric{" +
                "targetIdentifier=" + targetIdentifier.toString() +
                "metricName=" + name +
                "}";
    }
}
