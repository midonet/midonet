/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import java.net.URI;

import javax.xml.bind.annotation.XmlRootElement;

import com.midokura.midonet.smoketest.mgmt.DtoTenant;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

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
        return null;
    }

    public void delete() {
        // TODO Auto-generated method stub
    }

}
