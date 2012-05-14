/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoPortGroup;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class PortGroup {
    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant tenant;
        DtoPortGroup group;

        public Builder(MidolmanMgmt mgmt, DtoTenant tenant) {
            this.mgmt = mgmt;
            this.tenant = tenant;
            this.group = new DtoPortGroup();
        }

        public Builder setName(String name) {
            group.setName(name);
            return this;
        }

        public PortGroup build() {
            if (null == group.getName() || group.getName().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "port group with a null or empty name.");
            return new PortGroup(mgmt, mgmt.addPortGroup(tenant, group));
        }
    }

    private MidolmanMgmt mgmt;
    public DtoPortGroup group;

    PortGroup(MidolmanMgmt mgmt, DtoPortGroup group) {
        this.mgmt = mgmt;
        this.group = group;
    }

    public void delete() {
        mgmt.delete(group.getUri());
    }

    public UUID getId() {
        return group.getId();
    }
}
