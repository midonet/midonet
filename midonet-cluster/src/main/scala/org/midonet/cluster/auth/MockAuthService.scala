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

import scala.collection.JavaConversions._

import com.google.inject.Inject
import com.typesafe.config.Config

import org.midonet.cluster.rest_api.models.Tenant

/**
 * An implementation of the [[AuthService]] that always returns the admin tenant
 * regardless of the credentials. This is primarily for testing purposes.
 */
class MockAuthService @Inject()(conf: Config) extends AuthService {

    val mockAdminTenant =
        new Tenant("admin", "admin", "Admin Tenant", true)
    val adminToken = "00000000"

    private val tenants = new util.HashMap[String, Tenant]()
    tenants.put(mockAdminTenant.id, mockAdminTenant)

    // Allow tests to manipulate it
    def addTenant(id: String, name: String): Unit = {
        tenants.put(id, new Tenant(id, name, name, true))
    }

    def clearTenants(): Unit = {
        tenants.clear()
    }

    override def authenticate(username: String, password: String,
                              tenant: Option[String]): Token = {
        new Token(adminToken, null)
    }

    override def authorize(token: String): UserIdentity = {
        val uid = new UserIdentity(mockAdminTenant.id, mockAdminTenant.name,
                                   mockAdminTenant.id + "-user", token)
        uid.addRole(AuthRole.ADMIN)
        uid
    }

    override def tenant(token: String, id: String): Tenant = {
        if (id == "admin") mockAdminTenant else new Tenant(id, id, id, true)
    }

    override def tenants(token: String, marker: Option[String], limit: Option[Int])
    : Seq[Tenant] = {
        tenants.values().toSeq
    }
}
