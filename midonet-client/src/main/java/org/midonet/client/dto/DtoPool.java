/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

@XmlRootElement
public class DtoPool {
    private UUID id;
    private String name;
    private String description;
    private UUID subnetId;
    private UUID healthMonitorId;
    private String protocol;
    private String lbMethod;
    private boolean adminStateUp = true;
    private String status;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getSubnetId() {
        return subnetId;
    }

    public void setSubnetId(UUID subnetId) {
        this.subnetId = subnetId;
    }

    public UUID getHealthMonitorId() {
        return healthMonitorId;
    }

    public void setHealthMonitorId(UUID healthMonitorId) {
        this.healthMonitorId = healthMonitorId;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getLbMethod() {
        return lbMethod;
    }

    public void setLbMethod(String lbMethod) {
        this.lbMethod = lbMethod;
    }

    public boolean isAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoPool dtoPool = (DtoPool) o;

        if (adminStateUp != dtoPool.adminStateUp) return false;
        if (description != null ?
                !description.equals(dtoPool.description)
                : dtoPool.description != null) return false;
        if (healthMonitorId != null ?
                !healthMonitorId.equals(dtoPool.healthMonitorId)
                : dtoPool.healthMonitorId != null)
            return false;
        if (lbMethod != null ? !lbMethod.equals(dtoPool.lbMethod)
                : dtoPool.lbMethod != null) return false;
        if (name != null ? !name.equals(dtoPool.name)
                : dtoPool.name != null) return false;
        if (protocol != null ? !protocol.equals(dtoPool.protocol)
                : dtoPool.protocol != null) return false;
        if (status != null ? !status.equals(dtoPool.status)
                : dtoPool.status != null) return false;
        if (subnetId != null ? !subnetId.equals(dtoPool.subnetId)
                : dtoPool.subnetId != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result
                + (description != null ? description.hashCode() : 0);
        result = 31 * result + (subnetId != null ? subnetId.hashCode() : 0);
        result = 31 * result
                + (healthMonitorId != null ? healthMonitorId.hashCode() : 0);
        result = 31 * result + (protocol != null ? protocol.hashCode() : 0);
        result = 31 * result + (lbMethod != null ? lbMethod.hashCode() : 0);
        result = 31 * result + (adminStateUp ? 1 : 0);
        result = 31 * result + (status != null ? status.hashCode() : 0);
        return result;
    }
}
