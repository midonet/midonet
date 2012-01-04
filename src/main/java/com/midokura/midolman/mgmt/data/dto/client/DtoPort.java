/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoPort {
    private UUID id = null;
    private UUID deviceId = null;
    private UUID vifId = null;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getVifId() {
        return vifId;
    }

    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

}
