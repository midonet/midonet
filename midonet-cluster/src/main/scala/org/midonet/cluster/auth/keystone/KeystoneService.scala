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

package org.midonet.cluster.auth.keystone

import java.util.Date

import scala.collection.JavaConverters._

import com.google.inject.Inject
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory

import org.midonet.cluster.auth
import org.midonet.cluster.auth._
import org.midonet.cluster.keystoneLog
import org.midonet.cluster.rest_api.models.Tenant

class KeystoneService @Inject()(config: Config) extends AuthService {

    private val log = Logger(LoggerFactory.getLogger(keystoneLog))
    private val keystoneConfig = new KeystoneConfig(config)
    private val keystoneClient = new KeystoneClient(keystoneConfig)

    /**
      * Authenticates the user with the specified credentials. If the tenant
      * argument is set, the user is authenticated in the context of the
      * specified tenant. Otherwise, the authentication service must use a
      * default tenant, usually specified in the configuration.
      */
    @throws[AuthException]
    def authenticate(username: String, password: String,
                     someTenant: Option[String]): auth.Token = {
        log info s"Authenticating user $username for tenant $someTenant"

        val tenant = if (someTenant.isEmpty) {
            if (StringUtils.isBlank(keystoneConfig.projectName))
                throw new InvalidCredentialsException("Project missing")
            keystoneConfig.projectName
        } else someTenant.get

        tokenOf(keystoneClient.authenticate(tenant, username, password))
    }

    /**
      * Authorizes the specified token, and returns the corresponding user
      * identity if the token is valid.
      */
    @throws[AuthException]
    def authorize(token: String): UserIdentity = {
        log info s"Authorizing token $token"

        if (StringUtils.isBlank(token))
            throw new InvalidCredentialsException("No token was passed in.")

        val identity = identityOf(keystoneClient.validate(token))

        log info s"Token $token authorized as $identity"

        identity
    }

    /**
      * Returns the tenant with the specified identifier.
      */
    @throws[AuthException]
    def tenant(token: String, id: String): Tenant = {
        keystoneConfig.version match {
            case 2 => tenantOf(keystoneClient.getTenantById(id, token).tenant)
            case 3 => tenantOf(keystoneClient.getProjectById(id, token).project)
            case version => throw new KeystoneException(
                null, s"Operation not supported with Keystone version $version",
                null)
        }

    }

    /**
      * Returns the list of all tenants. If the `marker` is set, the method
      * returns the tenants starting after the given identifier. If the `limit`
      * is set, the method returns up to the given number of tenants.
      */
    @throws[AuthException]
    def tenants(token: String, marker: Option[String], limit: Option[Int])
    : Seq[Tenant] = {
        keystoneConfig.version match {
            case 2 =>
                keystoneClient.listTenants(token, marker, limit).tenants.asScala
                              .map(tenantOf)
            case 3 =>
                keystoneClient.listProjects(token).projects.asScala
                              .map(tenantOf)
            case version => throw new KeystoneException(
                null, s"Operation not supported with Keystone version $version",
                null)
        }
    }

    /**
      * Returns the token DTO for the given [[AuthResponse]] object.
      */
    private def tokenOf(response: AuthResponse): auth.Token = {
        new auth.Token(response.tokenId,
                       new Date(KeystoneClient.parseExpiresAt(
                           response.token.expiresAt)))
    }

    /**
      * Returns the user identity for the given [[AuthResponse]] object.
      */
    private def identityOf(response: AuthResponse): UserIdentity = {
        val identity = new UserIdentity(response.project.id,
                                        response.project.name,
                                        response.user.id,
                                        response.tokenId)

        for (role <- response.roles.asScala) {
            val midoRole = roleOf(role.name)
            if (midoRole.nonEmpty) {
                identity addRole midoRole.get
            }
        }
        identity
    }

    /**
      * Returns the MidoNet role for the specified Keystone role.
      */
    private def roleOf(role: String): Option[String] = {
        val lowerCaseRole = role.toLowerCase
        if (lowerCaseRole == keystoneConfig.adminRole)
            Some(AuthRole.ADMIN)
        else if (lowerCaseRole == keystoneConfig.tenantAdminRole)
            Some(AuthRole.TENANT_ADMIN)
        else if (lowerCaseRole == keystoneConfig.tenantUserRole)
            Some(AuthRole.TENANT_USER)
        else
            None
    }

    /**
      * Returns the tenant DTO for the given Keystone tenant object.
      */
    private def tenantOf(tenant: keystone.v2.Tenant): Tenant = {
        new Tenant(tenant.id, tenant.name, tenant.description, tenant.enabled)
    }

    /**
      * Returns the tenant DTO for the given Keystone project object.
      */
    private def tenantOf(project: keystone.v3.Project): Tenant = {
        new Tenant(project.id, project.name, project.description, project.enabled)
    }
}
