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

import com.google.common.base.MoreObjects;

import org.apache.commons.lang3.StringUtils;

/**
 * Class that holds the identity information of a user.
 */
public class UserIdentity {

    public final String tenantId;
    public final String tenantName;
    public final String userId;
    public final String token;

    private final Set<String> roles = new HashSet<>();

    public UserIdentity(String tenantId, String tenantName, String userId,
                        String token) {
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.userId = userId;
        this.token = token;
    }

    public void addRole(String role) {
        this.roles.add(role);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
            .add("userId", userId)
            .add("token", token)
            .add("tenantId", tenantId)
            .add("tenantName", tenantName)
            .add("roles", StringUtils.join(this.roles, '|'))
            .toString();
    }
}
