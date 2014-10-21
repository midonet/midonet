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
package org.midonet.api.auth;

/**
 * Class that defines roles.
 */
public class AuthRole {

    /**
     * Super admin role of the system.
     */
    public static final String ADMIN = "admin";

    /**
     * Tenant admin role.
     */
    public static final String TENANT_ADMIN = "tenant_admin";

    /**
     * Tenant user role.
     */
    public static final String TENANT_USER = "tenant_user";

    /**
     * Checks whether a given role is valid.
     *
     * @param role
     *            Role to check
     * @return True if role is valid
     */
    public static boolean isValidRole(String role) {
        if (role == null) {
            return false;
        }
        String lowerCaseRole = role.toLowerCase();
        return (lowerCaseRole.equals(ADMIN)
                || lowerCaseRole.equals(TENANT_ADMIN) || lowerCaseRole
                    .equals(TENANT_USER));
    }
}
