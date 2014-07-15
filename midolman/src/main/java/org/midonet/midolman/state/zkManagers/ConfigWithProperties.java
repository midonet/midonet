/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Preconditions;

import org.codehaus.jackson.annotate.JsonIgnore;

public abstract class ConfigWithProperties extends BaseConfig {

    private enum Key {

        tenantId("tenant_id");

        private final String name;

        private Key(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public Map<String, String> properties = new HashMap<>();

    @JsonIgnore
    public <K, V> void setProperty(K key, V val) {

        key = Preconditions.checkNotNull(key);
        val = Preconditions.checkNotNull(val);
        Preconditions.checkState(properties != null);

        properties.put(key.toString(), val.toString());
    }

    @JsonIgnore
    public <K> String getProperty(K key) {

        key = Preconditions.checkNotNull(key);
        Preconditions.checkState(properties != null);

        return properties.get(key.toString());
    }

    @JsonIgnore
    public <K> UUID getPropertyUuid(K key) {

        String val = getProperty(key);
        return val == null ? null : UUID.fromString(val);
    }

    @JsonIgnore
    public void setTenantId(String tenantId) {
        setProperty(Key.tenantId.toString(), tenantId);
    }

    @JsonIgnore
    public String getTenantId() {
        return getProperty(Key.tenantId.toString());
    }
}
