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

import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset, ZonedDateTime}
import java.util.Collections
import java.util.logging.Logger

import javax.annotation.Nullable
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.UriBuilder

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Try

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider
import com.sun.jersey.api.client.config.DefaultClientConfig
import com.sun.jersey.api.client.filter.LoggingFilter
import com.sun.jersey.api.client.{Client, ClientHandlerException, ClientResponse, UniformInterfaceException}

import org.apache.commons.lang.StringUtils
import org.apache.commons.lang3.StringUtils.{isBlank, isNotBlank}

import org.midonet.cluster.auth.keystone.KeystoneClient.{AdminToken, TokenExpirationGuard, parseExpiresAt, parseTimestamp}

object KeystoneClient {

    /**
      * This ensures that clock difference between the client and the Keystone
      * server does not cause the client to use an expired token.
      */
    private val TokenExpirationGuard = 1 minute

    /**
      * Contains information about an administrative token.
      */
    private case class AdminToken(id: String,
                                  serverIssueTime: Long,
                                  clientIssueTime: Long,
                                  expirationTime: Long,
                                  isStatic: Boolean)

    /**
      * Parses the given timestamp from the ISO8601 date format to a Unix
      * timestamp. If any parsing fails, the method throws a [[KeystoneException]].
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

    /**
      * Parses the given expiration timestamp string from the ISO8601 date
      * format to a Unix timestamp. If the string is `null`, meaning the
      * token never expires, the method returns the maximum long value.
      */
    @throws[KeystoneException]
    private[keystone] def parseExpiresAt(@Nullable string: String): Long = {
        if (string eq null)
            Long.MaxValue
        else
            parseTimestamp(string)
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
            AdminToken(config.adminToken, 0L, 0L, Long.MaxValue, isStatic = true)
        else null

    /**
      * Gets the current Keystone version.
      */
    @throws[KeystoneException]
    def getVersion: KeystoneVersion = {
        get(classOf[KeystoneVersion], None, None)()
    }

    /**
      * Authenticates against Keystone for the given tenant using the specified
      * password credentials. The method returns an [[AuthResponse]] instance
      * containing an authorization token for the given user.
      */
    @throws[KeystoneException]
    def authenticate(tenantName: String, userName: String, password: String)
    : AuthResponse = withVersion {
        case 2 =>
            val auth = v2.KeystoneAuth(v2.PasswordAuth(
                v2.PasswordCredentials(userName, password),tenantName))
            val access = post(auth, classOf[v2.KeystoneAccess], token = None,
                              "tokens")()
            AuthResponse(tokenId = access.access.token.id, r2 = access.access)
        case 3 =>
            val domain =
                if (StringUtils.isNotBlank(config.domainId))
                    v3.Domain(id = config.domainId)
                else
                    v3.Domain(name = config.domainName)
            val auth = v3.KeystoneAuth(v3.Auth(
                v3.Identity(methods = Collections.singletonList("password"),
                            password = v3.Password(v3.User(
                                name = userName,
                                password = password,
                                domain = domain))),
                v3.Scope(v3.Project(name = tenantName,
                                    domain = domain))))
            handleAuthResponse(post(auth, classOf[ClientResponse], token = None,
                                    "auth", "tokens")())
    }

    /**
      * Authenticates against Keystone for the given tenant using the specified
      * token. The method returns an [[AuthResponse]] instance containing an
      * authorization token for the user of the given token.
      */
    @throws[KeystoneException]
    def authenticate(tenantName: String, token: String): AuthResponse = withVersion {
        case 2 =>
            val access = post(v2.KeystoneAuth(v2.TokenAuth(v2.Token(id = token),
                                                           tenantName)),
                              classOf[v2.KeystoneAccess], token = None, "tokens")()
            AuthResponse(tokenId = access.access.token.id, r2 = access.access)
        case 3 =>
            val auth = v3.KeystoneAuth(v3.Auth(v3.Identity(
                methods = Collections.singletonList("token"),
                token = v3.Token(id = token))))
            handleAuthResponse(post(auth, classOf[ClientResponse], token = None,
                                    "auth", "tokens")())
    }

    /**
      * Validates an authorization token using the [[KeystoneClient]]'s
      * administrative credentials. If the `tenantScope` argument is true, then
      * the method validates the token in the specified tenant scope for
      * performance.
      */
    @throws[KeystoneException]
    def validate(token: String, tenantScope: Option[String] = None): AuthResponse = {
        var params = Seq.empty[(String, String)]
        if (tenantScope.nonEmpty)
            params = params :+ ("belongsTo", tenantScope.get)
        withAdminToken() { adminToken =>
            withVersion {
                case 2 =>
                    val access = get(classOf[v2.KeystoneAccess], Some(adminToken),
                                     None, "tokens", token)(params: _*)
                    AuthResponse(tokenId = access.access.token.id,
                                 r2 = access.access)
                case 3 =>
                    handleAuthResponse(get(classOf[ClientResponse], Some(adminToken),
                                           Some(token), "auth", "tokens")())
            }
        }
    }

    /**
      * Lists the users for the given client token. The client must be an
      * administrator.
      */
    @throws[KeystoneException]
    def listUsers(token: String): v2.KeystoneUsers = {
        withVersion {
            case 2 =>
                get(classOf[v2.KeystoneUsers], Some(token), subjectToken = None,
                    "users")()
        }
    }

    /**
      * Gets the information for the user with the specified name. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getUserByName(name: String, token: String): v2.KeystoneUser = {
        withVersion {
            case 2 =>
                get(classOf[v2.KeystoneUser], Some(token), subjectToken = None,
                    "users")(("name", name))
        }
    }

    /**
      * Gets the information for the user with the specified identifier. The
      * client must be an administrator.
      */
    @throws[KeystoneException]
    def getUserById(id: String, token: String): v2.KeystoneUser = {
        withVersion {
            case 2 =>
                get(classOf[v2.KeystoneUser], Some(token), subjectToken = None,
                    "users", id)()
        }
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
    : v2.KeystoneTenants = {
        withVersion {
            case 2 =>
                var params = Seq.empty[(String, String)]
                if (marker.isDefined) params = params :+("marker", marker.get)
                if (limit.isDefined) params = params :+("limit", limit.get.toString)
                get(classOf[v2.KeystoneTenants], Some(token), subjectToken = None,
                    "tenants")(params: _*)
        }
    }

    /**
      * Gets the information for the tenant with the specified name. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantByName(name: String, token: String)
    : v2.KeystoneTenant = {
        withVersion {
            case 2 =>
                get(classOf[v2.KeystoneTenant], Some(token), subjectToken = None,
                    "tenants")(("name", name))
        }
    }

    /**
      * Gets the information for the tenant with the specified identifier. The
      * client must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantById(id: String, token: String)
    : v2.KeystoneTenant = {
        withVersion {
            case 2 =>
                get(classOf[v2.KeystoneTenant], Some(token), subjectToken = None,
                    "tenants", id)()
        }
    }

    /**
      * Gets the list of roles for the user on the specified tenant. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getTenantUserRoles(tenantId: String, userId: String, token: String,
                           marker: Option[String] = None,
                           limit: Option[Int] = None)
    : v2.KeystoneRoles = withVersion {
        case 2 =>
            var params = Seq.empty[(String, String)]
            if (marker.isDefined) params = params :+ ("marker", marker.get)
            if (limit.isDefined) params = params :+ ("limit", limit.get.toString)
            get(classOf[v2.KeystoneRoles], Some(token), subjectToken = None,
                "tenants", tenantId, "users", userId, "roles")(params: _*)
    }

    /**
      * Lists the projects for the given client token. The client must be an
      * administrator.
      */
    @throws[KeystoneException]
    def listProjects(token: String): v3.KeystoneProjects = {
        withVersion {
            case 3 =>
                get(classOf[v3.KeystoneProjects], Some(token), subjectToken = None,
                    "projects")()
        }
    }

    /**
      * Gets the information for the project with the specified name. The client
      * must be an administrator.
      */
    @throws[KeystoneException]
    def getProjectByName(name: String, token: String): v3.KeystoneProjects = {
        withVersion {
            case 3 =>
                get(classOf[v3.KeystoneProjects], Some(token), subjectToken = None,
                    "projects")(("name", name))
        }
    }

    /**
      * Gets the information for the project with the specified identifier. The
      * client must be an administrator.
      */
    @throws[KeystoneException]
    def getProjectById(id: String, token: String): v3.KeystoneProject = {
        withVersion {
            case 3 =>
                get(classOf[v3.KeystoneProject], Some(token), subjectToken = None,
                    "projects", id)()
        }
    }

    /**
      * Gets the Keystone URI for the given path segments and query
      * parameters.
      */
    private def uriFor(paths: String*)(params: (String, String)*): URI = {
        val version = withVersion { case 2 => "v2.0" case 3 => "v3" }
        val uriPath =
            if (StringUtils.isNotBlank(config.urlOverride))
                config.urlOverride
            else
                s"${config.protocol}://${config.host}:${config.port}/$version"

        val builder = UriBuilder
            .fromPath(uriPath)
            .segment(paths: _*)
        for ((param, value) <- params)
            builder.queryParam(param, value)
        builder.build()
    }

    @throws[KeystoneException]
    @inline
    private def withVersion[R](pf: PartialFunction[Int, R]): R = {
        pf.applyOrElse(
            config.version,
            (v: Int) =>
                throw new KeystoneException(
                    null, s"Unsupported Keystone version ${config.version}", null))
    }

    /**
      * A GET HTTP request to the Keystone server.
      */
    private def get[T](clazz: Class[T], authToken: Option[String],
                       subjectToken: Option[String], paths: String*)
                      (params: (String, String)*): T = {
        val uri = uriFor(paths: _*)(params: _*)
        tryRequest(uri) {
            val request = client.resource(uri)
                                .`type`(APPLICATION_JSON)
                                .accept(APPLICATION_JSON)
            if (authToken.isDefined) {
                request.header("X-Auth-Token", authToken.get)
            }
            if (subjectToken.isDefined) {
                request.header("X-Subject-Token", subjectToken.get)
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
        val uriString = if (uri ne null) uri.toString else null
        try {
            f
        } catch {
            case e: UniformInterfaceException
                if e.getResponse.getStatus == Status.UNAUTHORIZED.getStatusCode =>
                throw new KeystoneUnauthorizedException(uriString, e)
            case e: UniformInterfaceException =>
                throw new KeystoneException(uriString, e)
            case e: ClientHandlerException =>
                throw new KeystoneConnectionException(uriString, e)
        }
    }

    /**
      * Calls a function with a current administrative token.
      */
    @tailrec
    private def withAdminToken[R](authenticateOnUnauthorized: Boolean = true)
                                 (f: (String) => R): R = {
        // If there exists a current administrative token.
        var currentToken = adminToken
        // If there is no token or if the token has or is about to expire, renew
        // the token using the password credentials.
        if ((currentToken eq null) ||
            System.currentTimeMillis() + TokenExpirationGuard.toMillis +
            currentToken.serverIssueTime - currentToken.clientIssueTime >=
            currentToken.expirationTime) {

            if (isBlank(config.userName) || isBlank(config.password) ||
                isBlank(config.projectName)) {
                throw new KeystoneException(null,
                    "Keystone configuration must provide an administrative " +
                    "tenant, user name and password", null)
            }

            val auth = authenticate(config.projectName, config.userName,
                                    config.password)
            if (auth.token eq null) {
                throw new KeystoneUnauthorizedException(
                    null, "Keystone failed to return an authentication token", null)
            }

            currentToken = AdminToken(
                id = auth.tokenId,
                serverIssueTime = parseTimestamp(auth.token.issuedAt),
                clientIssueTime = System.currentTimeMillis(),
                expirationTime = parseExpiresAt(auth.token.expiresAt),
                isStatic = false)
            adminToken = currentToken
        }
        try return f(currentToken.id)
        catch {
            case _: KeystoneUnauthorizedException
                if authenticateOnUnauthorized && !adminToken.isStatic =>
                adminToken = null
        }
        withAdminToken(authenticateOnUnauthorized = false)(f)
    }

    /**
      * Handles the authentication response from the Keystone v3 API server and
      * returns and [[AuthResponse]] object.
      */
    private def handleAuthResponse(response: ClientResponse): AuthResponse = {
        val tokenId = response.getHeaders.getFirst("X-Subject-Token")

        if (response.getStatus < 300) {
            val token = response.getEntity(classOf[v3.KeystoneToken]).token
            return AuthResponse(tokenId = tokenId, r3 = token)
        }
        throw new KeystoneUnauthorizedException(response)
    }

}
