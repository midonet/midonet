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

    private List<KeystoneTenant> tenants = new ArrayList<KeystoneTenant>();

    public List<KeystoneTenant> getTenants() {
        return tenants;
    }

    public void setTenants(List<KeystoneTenant> tenants) {
        this.tenants = tenants;
    }

    @Override
    public List<? extends Tenant> get() {
        return tenants;
    }

    /**
     * Class representing a single tenant entity in a deserialized format
     * of the HTTP response body from the Keystone API v2.0 when getting a
     * list of tenants.
     */
    public static class KeystoneTenant implements Tenant {

        private String id;
        private String name;
        private String description;
        private boolean enabled;

        @Override
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @Override
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

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String toString() {

            StringBuilder sb = new StringBuilder();
            sb.append("id=");
            sb.append(this.getId());
            sb.append(", name=");
            sb.append(this.getName());
            sb.append(", enabled=");
            sb.append(this.isEnabled());
            sb.append(", description=");
            sb.append(this.getDescription());
            return sb.toString();
        }

        @Override
        public boolean equals(Object other) {

            KeystoneTenant o = (KeystoneTenant) other;
            if (o == null) {
                return false;
            }

            if (o == this) {
                return true;
            }

            if (!this.getId().equals(o.getId())) {
                 return false;
            }

            if (!this.getName().equals(o.getName())) {
                return false;
            }

            if (!this.getDescription().equals(o.getDescription())) {
                return false;
            }

            if (this.isEnabled() != o.isEnabled()) {
                return false;
            }

            return true;
        }
    }
}
