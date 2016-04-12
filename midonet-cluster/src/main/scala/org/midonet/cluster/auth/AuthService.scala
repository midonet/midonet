/*
 * Copyright 2016 Midokura SARL
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

import org.midonet.cluster.rest_api.models.Tenant

/**
  * An interface for an authentication service.
  */
trait AuthService {

    /**
      * Authenticates the user with the specified credentials. If the tenant
      * argument is set, the user is authenticated in the context of the
      * specified tenant. Otherwise, the authentication service must use a
      * default tenant, usually specified in the configuration.
      */
    @throws[AuthException]
    def authenticate(username: String, password: String, tenant: Option[String])
    : Token

    /**
      * Authorizes the specified token, and returns the corresponding user
      * identity if the token is valid.
      */
    @throws[AuthException]
    def authorize(token: String): UserIdentity

    /**
      * Returns the tenant with the specified identifier.
      */
    @throws[AuthException]
    def tenant(token: String, id: String): Tenant

    /**
      * Returns the list of all tenants. If the `marker` is set, the method
      * returns the tenants starting after the given identifier. If the `limit`
      * is set, the method returns up to the given number of tenants.
      */
    @throws[AuthException]
    def tenants(token: String, marker: Option[String], limit: Option[Int])
    : Seq[Tenant]

}
