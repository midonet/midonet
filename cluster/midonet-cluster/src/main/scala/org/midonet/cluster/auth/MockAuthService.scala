/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.auth

import java.util

import javax.servlet.http.HttpServletRequest

import scala.collection.JavaConversions._

/**
 * An implementation of the [[AuthService]] that always returns the admin tenant
 * regardless of the credentials. This is primarily for testing purposes.
 */
class MockAuthService extends AuthService {

    val mockAdminTenant = new Tenant {
        override def getName: String = "admin"
        override def getId: String = "admin"
    }

    val adminToken = "00000000"

    override def getUserIdentityByToken(token: String): UserIdentity = {
        val uid = new UserIdentity
        uid.setTenantId(mockAdminTenant.getId)
        uid.setToken(token)
        uid.setTenantName(mockAdminTenant.getName)
        uid.setUserId(mockAdminTenant.getId + "-user")
        uid
    }

    override def getTenants(request: HttpServletRequest): util.List[Tenant] = {
        List(mockAdminTenant)
    }

    override def getTenant(id: String): Tenant = new Tenant {
        override def getName: String = id
        override def getId: String = id
    }

    override def login(username: String, password: String,
                       request: HttpServletRequest): Token = {
        new Token(adminToken, null)
    }

}
