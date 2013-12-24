/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.client.dto;

import com.google.common.base.Objects;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

@XmlRootElement
public class DtoLoadBalancer {
    private UUID id;
    private boolean adminStateUp = true;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public boolean getAdminStateUp() {
        return adminStateUp;
    }

    public void setAdminStateUp(boolean adminStateUp) {
        this.adminStateUp = adminStateUp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DtoLoadBalancer that = (DtoLoadBalancer) o;

        if (!Objects.equal(this.id, that.getId())) return false;
        if (adminStateUp != that.adminStateUp) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (id != null) ? id.hashCode() : 0;
        result = 31 * result
                + (adminStateUp ? 1 : 0);
        return result;
    }
}
