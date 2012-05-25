/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.util.UUID;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Date: 5/25/12
 */

@XmlRootElement
public class DtoMetricTarget {
    UUID targetUUID;
    String type;

    public UUID getTargetUUID() {
        return targetUUID;
    }

    public void setTargetUUID(UUID targetUUID) {
        this.targetUUID = targetUUID;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
