/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone.v2_0;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.midonet.api.auth.Tenant;
import org.midonet.api.auth.TenantList;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;

/**
 * Class representing the response body from the Keystone API v2.0 call to get a
 * list of tenants.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeystoneTenantList implements TenantList {

    private List<KeystoneTenant.KeystoneTenantEntity> tenants =
            new ArrayList<KeystoneTenant.KeystoneTenantEntity>();

    public List<KeystoneTenant.KeystoneTenantEntity> getTenants() {
        return tenants;
    }

    public void setTenants(List<KeystoneTenant.KeystoneTenantEntity> tenants) {
        this.tenants = tenants;
    }

    @Override
    public List<? extends Tenant> get() {
        return tenants;
    }
}
