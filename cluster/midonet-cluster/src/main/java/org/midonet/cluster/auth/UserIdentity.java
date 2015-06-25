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

import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Class that holds the identity information of a user.
 */
public class UserIdentity {

    private String tenantId = null;
    private String tenantName = null;
    private String userId = null;
    private String token = null;
    private final Set<String> roles = new HashSet<String>();

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public void addRole(String role) {
        this.roles.add(role);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public String getTenantName() {
        return tenantName;
    }

    public void setTenantName(String tenantName) {
        this.tenantName = tenantName;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("UserIdentity: userId=" + this.userId);
        sb.append(", token=" + this.token);
        sb.append(", tenantId=" + this.tenantId);
        sb.append(", tenantName=" + this.tenantName);
        sb.append(", roles=" + StringUtils.join(this.roles, '|'));
        return sb.toString();
    }
}
