/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone.v2_0;

import com.google.common.base.Objects;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.midonet.api.auth.Tenant;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing a single tenant entity in a deserialized format
 * of the HTTP response body from the Keystone API v2.0
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeystoneTenant {

    private KeystoneTenantEntity tenant;

    public KeystoneTenantEntity getTenant() {
        return this.tenant;
    }

    public void setTenant(KeystoneTenantEntity tenant) {
        this.tenant = tenant;
    }

    public static class KeystoneTenantEntity implements Tenant {

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
        public int hashCode() {
            return Objects.hashCode(id, name, description, enabled);
        }

        @Override
        public boolean equals(Object other) {

            if (other == this) {
                return true;
            }

            if (other == null || getClass() != other.getClass()) {
                return false;
            }

            KeystoneTenantEntity o = (KeystoneTenantEntity) other;
            if (!Objects.equal(this.getId(), o.getId())) {
                return false;
            }

            if (!Objects.equal(this.getName(), o.getName())) {
                return false;
            }

            if (!Objects.equal(this.getDescription(), o.getDescription())) {
                return false;
            }

            if (this.isEnabled() != o.isEnabled()) {
                return false;
            }

            return true;
        }
    }
}