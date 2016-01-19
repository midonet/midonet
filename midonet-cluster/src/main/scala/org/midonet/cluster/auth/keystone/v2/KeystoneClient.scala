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

package org.midonet.cluster.auth.keystone.v2

import java.net.URI
import java.time.{ZoneOffset, LocalDateTime, ZonedDateTime}
import java.time.format.{DateTimeParseException, DateTimeFormatter}
import java.util.logging.Logger

import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.UriBuilder

import scala.concurrent.duration._
import scala.util.Try

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider
import com.sun.jersey.api.client.config.DefaultClientConfig
import com.sun.jersey.api.client.filter.LoggingFilter
import com.sun.jersey.api.client.{Client, ClientHandlerException, UniformInterfaceException}

import org.apache.commons.lang3.StringUtils.{isBlank, isNotBlank}

import org.midonet.cluster.auth.keystone.v2.KeystoneClient._

object KeystoneClient {

    /**
      * This specifies the interval before the expiration time of the
      * administrative token when it will no longer be used. This ensures that
      * clock difference between the client and the Keystone server does not
      * cause the client to use an expired token.
      */
    private val TokenExpirationGuard = 1 minute

    /**
      * Contains information about an administrative token.
      */
    private case class AdminToken(id: String,
                                  serverIssueTime: Long,
                                  clientIssueTime: Long,
                                  expirationTime: Long)

    /**
      * Parses the given timestamp from the two supported date formats: ISO8601
      * and JDBC to a Unix timestamp. If any parsing fails, the method returns
      * zero.
      */
    @throws[KeystoneException]
    private[keystone] def parseTimestamp(string: String): Long = {
        val tryZonedTime = Try {
            ZonedDateTime.parse(string, DateTimeFormatter.ISO_DATE_TIME)
                         .toInstant.toEpochMilli
        }
        val tryLocalTime = Try {
            LocalDateTime.parse(string, DateTimeFormatter.ISO_DATE_TIME)
                         .toInstant(ZoneOffset.of("Z")).toEpochMilli
        }

        tryZonedTime recoverWith {
            case _ => tryLocalTime
        } getOrElse {
            throw new KeystoneException(
                null, s"Unknown token timestamp format for $string", null)
        }
    }
}


class KeystoneClient(config: KeystoneConfig) {

    private val jsonProvider = new JacksonJaxbJsonProvider()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val clientConfig = new DefaultClientConfig()
    clientConfig.getSingletons.add(jsonProvider)

    private val client = Client.create(clientConfig)
    client.addFilter(new LoggingFilter(
        Logger.getLogger("org.midonet.cluster.auth.keystone")))

    @volatile private var adminToken =
        if (isNotBlank(config.adminToken))
            AdminToken(config.adminToken, 0L, 0L, Long.MaxValue)
        else null

    /**
      * Gets the current Keystone version.
      */
    @throws[KeystoneException]
    def getVersion: KeystoneVersion = {
        get(classOf[KeystoneVersion], None)()
    }

    /**
      * Authenticates against Keystone for the given tenant using the specified
      * password credentials. The method returns a [[KeystoneAccess]] instance
      * containing an authorization token for the given user.
      */
    @throws[KeystoneException]
    def authenticate(tenantName: String, userName: String, password: String)
    : KeystoneAccess = {
        post(KeystoneAuth(PasswordAuth(PasswordCredentials(userName, password),
                                      tenantName)),
             classOf[KeystoneAccess], None, "tokens")()
    }

    /**
      * Authenticates against Keystone for the given tenant using the specified
      * token. The method returns a [[KeystoneAccess]] instance containing an
      * authorization token for the user of the given token.
      */
    @throws[KeystoneException]
    def authenticate(tenantName: String, token: String): KeystoneAccess = {
        post(KeystoneAuth(TokenAuth(Token(id = token), tenantName)),
             classOf[KeystoneAccess], None, "tokens")()
    }

    /**
      * Validates an authorization token using the [[KeystoneClient]]'s
      * administrative credentials. If the `tenantScope` argument is true, then
      * the method validates the token in the specified tenant scope for
      * performance.
      */
    @throws[KeystoneException]
    def validate(token: String, tenantScope: Boolean = false): KeystoneAccess = {
        var params = Seq.empty[(String, String)]
        if (tenantScope) params = params :+ ("belongsTo", "")
        withAdminToken { adminToken =>
            get(classOf[KeystoneAccess], Some(adminToken), "tokens", token)(
                params: _*)
        }
    }

    /**
      * Lists the users for the given client token. The client must be an
      * administrator.
      */
    @throws[KeystoneException]
    def listUsers(token: String): KeystoneUsers = {
        get(classOf[KeystoneUsers], Some(token), "users")()
    }

    /**
      * Gets the information for the user with the specified name. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getUserByName(name: String, token: String): KeystoneUser = {
        get(classOf[KeystoneUser], Some(token), "users")(("name", name))
    }

    /**
      * Gets the information for the user with the specified identifier. The
      * client must be an administrator.
      */
    @throws[KeystoneException]
    def getUserById(id: String, token: String): KeystoneUser = {
        get(classOf[KeystoneUser], Some(token), "users", id)()
    }

    /**
      * Lists the tenants for the given client token. The client must be an
      * administrator.
      * @param marker Specifies the identifier of the last seen tenant when using
      *               multi-page listing.
      * @param limit Specifies a limit on the number of items returned by
      *              the operation when using multi-page listing.
      */
    @throws[KeystoneException]
    def listTenants(token: String, marker: Option[String] = None,
                    limit: Option[Int] = None)
    : KeystoneTenants = {
        var params = Seq.empty[(String, String)]
        if (marker.isDefined) params = params :+ ("marker", marker.get)
        if (limit.isDefined) params = params :+ ("limit", limit.get.toString)
        get(classOf[KeystoneTenants], Some(token), "tenants")(params: _*)
    }

    /**
      * Gets the information for the tenant with the specified name. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantByName(name: String, token: String): KeystoneTenant = {
        get(classOf[KeystoneTenant], Some(token), "tenants")(("name", name))
    }

    /**
      * Gets the information for the tenant with the specified identifier. The
      * client must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantById(id: String, token: String): KeystoneTenant = {
        get(classOf[KeystoneTenant], Some(token), "tenants", id)()
    }

    /**
      * Gets the list of roles for the user on the specified tenant. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantUserRoles(tenantId: String, userId: String, token: String,
                           marker: Option[String] = None,
                           limit: Option[Int] = None)
    : KeystoneRoles = {
        var params = Seq.empty[(String, String)]
        if (marker.isDefined) params = params :+ ("marker", marker.get)
        if (limit.isDefined) params = params :+ ("limit", limit.get.toString)
        get(classOf[KeystoneRoles], Some(token), "tenants", tenantId,
            "users", userId, "roles")(params: _*)
    }

    /**
      * Gets the Keystone URI for the given path segments and query
      * parameters.
      */
    private def uriFor(paths: String*)(params: (String, String)*): URI = {
        val builder = UriBuilder
            .fromPath(s"${config.protocol}://${config.host}:${config.port}/v2.0")
            .segment(paths: _*)
        for ((param, value) <- params)
            builder.queryParam(param, value)
        builder.build()
    }

    /**
      * A GET HTTP request to the Keystone server.
      */
    private def get[T](clazz: Class[T], token: Option[String], paths: String*)
                      (params: (String, String)*): T = {
        val uri = uriFor(paths: _*)(params: _*)
        tryRequest(uri) {
            val request = client.resource(uri)
                                .`type`(APPLICATION_JSON)
                                .accept(APPLICATION_JSON)
            if (token.isDefined) {
                request.header("X-Auth-Token", token.get)
            }
            request.get(clazz)
        }
    }

    /**
      * A POST HTTP request to the Keystone server.
      */
    private def post[T, R](obj: T, clazz: Class[R], token: Option[String],
                           paths: String*)
                          (params: (String, String)*): R = {
        val uri = uriFor(paths: _*)(params: _*)
        tryRequest(uri) {
            val request = client.resource(uri)
                                .`type`(APPLICATION_JSON)
                                .accept(APPLICATION_JSON)
            if (token.isDefined) {
                request.header("X-Auth-Token", token)
            }
            request.post(clazz, obj)
        }
    }

    /**
      * Provides exception handling for the given function.
      */
    private def tryRequest[R](uri: URI)(f: => R): R = {
        try {
            f
        } catch {
            case e: UniformInterfaceException
                if e.getResponse.getStatus == Status.UNAUTHORIZED.getStatusCode =>
                throw new KeystoneUnauthorizedException(uri.toString, e)
            case e: UniformInterfaceException =>
                throw new KeystoneException(uri.toString, e)
            case e: ClientHandlerException =>
                throw new KeystoneConnectionException(uri.toString, e)
        }
    }

    /**
      * Calls a function with a current administrative token.
      */
    private def withAdminToken[R](f: (String) => R): R = {
        // If there exists a current administrative token.
        var currentToken = adminToken
        // If there is no token or if the token has or is about to expire, renew
        // the token using the password credentials.
        if ((currentToken eq null) ||
            System.currentTimeMillis() + TokenExpirationGuard.toMillis +
            currentToken.serverIssueTime - currentToken.clientIssueTime >=
            currentToken.expirationTime) {

            if (isBlank(config.userName) || isBlank(config.password) ||
                isBlank(config.tenantName)) {
                throw new KeystoneException(null,
                    "Keystone configuration must provide an administrative " +
                    "tenant, user name and password", null)
            }

            val keystoneAccess = authenticate(config.tenantName,
                                              config.userName,
                                              config.password)
            val token = keystoneAccess.access.token
            currentToken = AdminToken(token.id,
                                      parseTimestamp(token.issuedAt),
                                      System.currentTimeMillis(),
                                      parseTimestamp(token.expires))
            adminToken = currentToken
        }
        f(currentToken.id)
    }

}
