/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class Tenant {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoTenant dtoTenant;

        public Builder(MidolmanMgmt mgmt) {
            this.mgmt = mgmt;
            dtoTenant = new DtoTenant();
        }

        public Builder setName(String name) {
            if (null == name || name.isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "tenant with a null or empty name.");
            dtoTenant.setId(name);
            return this;
        }

        public Tenant build() {
            if (null == dtoTenant.getId() || dtoTenant.getId().isEmpty())
                throw new IllegalArgumentException("Cannot create a "
                        + "tenant with a null or empty name.");
            return new Tenant(mgmt, mgmt.addTenant(dtoTenant));
        }
    }

    MidolmanMgmt mgmt;
    DtoTenant dto;

    Tenant(MidolmanMgmt mgmt, DtoTenant t) {
        this.mgmt = mgmt;
        this.dto = t;
    }

    public Router.Builder addRouter() {
        return new Router.Builder(mgmt, dto);
    }

    public Bridge.Builder addBridge() {
        return new Bridge.Builder(mgmt, dto);
    }

    public RuleChain.Builder addChain() {
        return new RuleChain.Builder(mgmt, dto);
    }

    public PortGroup.Builder addPortGroup() {
        return new PortGroup.Builder(mgmt, dto);
    }

    public void delete() {
        mgmt.delete(dto.getUri());
    }

}
