/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.auth;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

import org.codehaus.jackson.annotate.JsonIgnoreProperties;

/**
 * Class representing the response body from the Keystone API v2.0 call to get a
 * list of tenants.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeystoneTenantList implements TenantList {

    private List<KeystoneTenant.KeystoneTenantEntity> tenants =
            new ArrayList<>();

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
