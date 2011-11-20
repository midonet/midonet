/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

import com.midokura.midolman.mgmt.data.*;

public class Tenant {

    public static class Builder {
        MidolmanMgmt mgmt;
        com.midokura.midolman.mgmt.data.dto.Tenant dtoTenant;

        public Builder(MidolmanMgmt mgmt) {
            this.mgmt = mgmt;
            dtoTenant = new com.midokura.midolman.mgmt.data.dto.Tenant();
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
    com.midokura.midolman.mgmt.data.dto.Tenant dtoTenant;

    public Tenant(MidolmanMgmt mgmt,
            com.midokura.midolman.mgmt.data.dto.Tenant dtoTenant) {
        this.mgmt = mgmt;
        this.dtoTenant = dtoTenant;
    }

    public Router.Builder addRouter() {
        return null;
    }

    public void delete() {
        // TODO Auto-generated method stub

    }

}
