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

import java.text.SimpleDateFormat
import java.util
import java.util.{Date, TimeZone, UUID}

import javax.servlet.DispatcherType
import javax.ws.rs._
import javax.ws.rs.core.MediaType.APPLICATION_JSON
import javax.ws.rs.core.{Response, UriInfo}

import scala.Error
import scala.annotation.meta.{getter, param}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.util.ISO8601Utils
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider
import com.google.inject.servlet.{GuiceFilter, GuiceServletContextListener, RequestScoped}
import com.google.inject.{Guice, Inject}
import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer

import org.eclipse.jetty.server.{HttpConfiguration, HttpConnectionFactory, Server, ServerConnector}
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

import org.midonet.cluster.auth.keystone.KeystoneTest.{Error, KeystoneError, KeystoneContextListener, DateFormat}
import org.midonet.cluster.auth.keystone.KeystoneTest.DateFormat.DateFormat
import org.midonet.cluster.auth.keystone.v2._

object KeystoneTest {

    object DateFormat extends Enumeration {
        trait DateFormat { def asString(time: Long): String }
        final val Iso8601 = new DateFormat {
            override def asString(time: Long): String =
                ISO8601Utils.format(new Date(time))
        }
        final val Iso8601Micro = new DateFormat {
            override def asString(time: Long): String = {
                val format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'000Z'")
                format.setTimeZone(TimeZone.getTimeZone("UTC"))
                format.format(new Date(time))
            }
        }
    }

    class KeystoneContextListener(test: KeystoneTest)
        extends GuiceServletContextListener {
        protected override def getInjector = {
            Guice.createInjector(servlet(test))
        }
    }

    def servlet(test: KeystoneTest) = new JerseyServletModule {
        override def configureServlets(): Unit = {
            bind(classOf[JacksonJsonProvider])
                .toInstance(new JacksonJsonProvider(new ObjectMapper))
            bind(classOf[KeystoneTest])
                .toInstance(test)
            bind(classOf[KeystoneResource])
            serve("/*").`with`(classOf[GuiceContainer])
        }
    }

    case class Auth @JsonCreator()(
        @(JsonProperty @getter @param)("token") token: Token,
        @(JsonProperty @getter @param)("passwordCredentials") credentials: PasswordCredentials,
        @(JsonProperty @getter @param)("tenantName") tenantName: String)

    case class Error @JsonCreator()(
        @(JsonProperty @getter @param)("message") message: String,
        @(JsonProperty @getter @param)("code") code: Int,
        @(JsonProperty @getter @param)("title") title: String)

    case class KeystoneAuth @JsonCreator()(
        @(JsonProperty @getter @param)("auth") auth: Auth)

    case class KeystoneError @JsonCreator()(
        @(JsonProperty @getter @param)("error") error: Error)

    @RequestScoped
    @Path("/")
    class KeystoneResource @Inject()(info: UriInfo, test: KeystoneTest) {
        @Path("v2.0")
        def v2 = new V2Resource(info, test)
    }

    @RequestScoped
    class V2Resource(info: UriInfo, test: KeystoneTest) {
        @GET
        @Produces(Array(APPLICATION_JSON))
        def get: KeystoneVersion = {
            KeystoneVersion(Version(
                status = "stable",
                updated = test.currentDate,
                mediaTypes = List(
                    MediaType(base = "application/json",
                              `type` = "application/vnd.openstack.identity-v2.0+json")).asJava,
                id = "v2.0",
                links = List(
                    Link(href = s"${test.keystoneProtocol}://${test.keystoneHost}:${test.keystonePort}/v2.0",
                         rel = "self",
                         `type` = null)).asJava
            ))
        }

        @Path("tokens")
        def tokens = new TokensResource(info, test)

        @Path("users")
        def users = new UsersResource(info, test)

        @Path("tenants")
        def tenants = new TenantsResource(info, test)
    }

    @RequestScoped
    class TokensResource(info: UriInfo, test: KeystoneTest) {
        @POST
        @Consumes(Array(APPLICATION_JSON))
        def authenticate(auth: KeystoneAuth): KeystoneAccess = {
            test.authenticate(auth.auth)
        }

        @GET
        @Path("{id}")
        @Produces(Array(APPLICATION_JSON))
        def validate(@HeaderParam("X-Auth-Token") token: String,
                     @PathParam("id") id: String): KeystoneAccess = {
            test.validate(token, id)
        }
    }

    @RequestScoped
    class UsersResource(info: UriInfo, test: KeystoneTest) {
        @GET
        @Path("{id}")
        def get(@HeaderParam("X-Auth-Token") token: String,
                @PathParam("id") id: String): KeystoneUser = {
            test.userById(token, id)
        }

        @GET
        @Produces(Array(APPLICATION_JSON))
        def list(@HeaderParam("X-Auth-Token") token: String): AnyRef = {
            val name = info.getQueryParameters.getFirst("name")
            if (name eq null) test.users(token)
            else test.userByName(token, name)
        }
    }

    @RequestScoped
    class TenantsResource(info: UriInfo, test: KeystoneTest) {
        @GET
        @Path("{id}")
        def get(@HeaderParam("X-Auth-Token") token: String,
                @PathParam("id") id: String): KeystoneTenant = {
            test.tenantById(token, id)
        }

        @GET
        @Produces(Array(APPLICATION_JSON))
        def list(@HeaderParam("X-Auth-Token") token: String): AnyRef = {
            val name = info.getQueryParameters.getFirst("name")
            if (name eq null) test.tenants(token)
            else test.tenantByName(token, name)
        }

        @GET
        @Path("{tenantId}/users/{userId}/roles")
        def roles(@HeaderParam("X-Auth-Token") token: String,
                  @PathParam("tenantId") tenantId: String,
                  @PathParam("userId") userId: String): KeystoneRoles = {
            test.userRoles(token, userId)
        }
    }
}

class KeystoneTest extends FlatSpec with BeforeAndAfterAll {

    private val random = new Random

    private val tokens = new mutable.HashMap[String, (User, Token)]
    private val tenantsById = new mutable.HashMap[String, Tenant]
    private val tenantsByName = new mutable.HashMap[String, Tenant]
    private val usersById = new mutable.HashMap[String, (User, String)]
    private val usersByUserName = new mutable.HashMap[String, (User, String)]
    private val usersAdmin = new mutable.HashMap[String, Boolean]

    protected val keystoneProtocol = "http"
    protected val keystoneHost = "127.0.0.1"
    protected val keystonePort = 30000 + random.nextInt(30000)
    protected val keystoneTenant = "admin"
    protected val keystoneUser = "admin"
    protected val keystonePassword = "midonet"
    protected val keystoneToken = "somelongtesttoken"

    protected var dateFormat: DateFormat = DateFormat.Iso8601
    protected var currentTime = System.currentTimeMillis()
    protected var tokenLifetime = 30 * 60 * 1000L

    private var server: Server = _

    protected val currentDate = dateFormat.asString(currentTime)

    private def connector(server: Server): ServerConnector = {
        val config = new HttpConfiguration()
        val connector = new ServerConnector(server, new HttpConnectionFactory(config))
        connector.setHost(keystoneHost)
        connector.setPort(keystonePort)
        connector.setIdleTimeout(30000)
        connector
    }

    private def handler(server: Server): ServletContextHandler = {
        val allDispatchers = util.EnumSet.allOf(classOf[DispatcherType])
        val handler = new ServletContextHandler(server, "/",
                                                ServletContextHandler.SESSIONS)
        handler.addEventListener(new KeystoneContextListener(this))
        handler.addFilter(classOf[GuiceFilter], "/*", allDispatchers)
        handler.addServlet(classOf[DefaultServlet], "/*")
        handler.setClassLoader(Thread.currentThread().getContextClassLoader)
        handler
    }

    protected override def beforeAll(): Unit = {
        addTenant(Tenant(id = UUID.randomUUID.toString,
                         enabled = true,
                         name = "admin",
                         description = "Admin tenant"))
        addUser(User(id = UUID.randomUUID.toString,
                     userName = "admin",
                     name = "admin",
                     enabled = true,
                     email = "admin@example.com",
                     roles = List(Role(id = UUID.randomUUID.toString,
                                       name = "admin",
                                       description = "Admin role")).asJava,
                     roleLinks = null),
                password = keystonePassword,
                isAdmin = true)

        server = new Server()
        server.setConnectors(Array(connector(server)))
        server.setHandler(handler(server))
        server.start()
    }

    protected override def afterAll(): Unit = {
        try {
            server.stop()
            server.join()
        } finally {
            server.destroy()
        }
    }

    private def addTenant(tenant: Tenant): Unit = {
        tenantsById += tenant.id -> tenant
        tenantsByName += tenant.name -> tenant
    }

    private def addUser(user: User, password: String, isAdmin: Boolean): Unit = {
        usersById += user.id -> (user, password)
        usersByUserName += user.userName -> (user, password)
        usersAdmin += user.id -> isAdmin
    }

    private def authenticate(auth: KeystoneTest.Auth): KeystoneAccess = {
        expireTokens()
        val tenant = tenantsByName.getOrElse(auth.tenantName,
                                             throw unauthorizedError())

        val user = if (auth.token ne null) {
            val (user, _) = tokens.getOrElse(auth.token.id,
                                             throw unauthorizedError())
            user
        } else if (auth.credentials ne null) {
            val (user, password) =
                usersByUserName.getOrElse(auth.credentials.userName,
                                          throw unauthorizedError())
            // Check user password.
            if (password != auth.credentials.password) throw unauthorizedError()
            user
        } else throw unauthorizedError()

        // Check user disabled.
        if (!user.enabled) throw unauthorizedError()

        val token = Token(
            UUID.randomUUID.toString,
            issuedAt = dateFormat.asString(currentTime),
            expires = dateFormat.asString(currentTime + tokenLifetime),
            tenant = tenant,
            auditIds = null)
        tokens += token.id -> (user, token)
        KeystoneAccess(Access(
            token = token,
            user = user,
            metadata = Metadata(
                isAdmin = usersAdmin(user.id),
                roles = user.roles.asScala.map(_.id).asJava),
            serviceCatalog = null,
            trust = null))
    }

    private def authorize(token: String): Unit = {
        expireTokens()
        if ((token != keystoneToken) && !tokens.contains(token))
            throw unauthorizedError()
    }

    private def validate(tok: String, id: String): KeystoneAccess = {
        authorize(tok)
        val (user, token) = tokens.getOrElse(id, throw tokenNotFoundError(id))
        KeystoneAccess(Access(
            token = token,
            user = user,
            metadata = Metadata(
                isAdmin = usersAdmin(user.id),
                roles = user.roles.asScala.map(_.id).asJava),
            serviceCatalog = null,
            trust = null))
    }

    private def users(token: String): KeystoneUsers = {
        authorize(token)
        KeystoneUsers(users = usersById.values.map(_._1).toList.asJava)
    }

    private def userById(token: String, id: String): KeystoneUser = {
        authorize(token)
        val (user, _) = usersById.getOrElse(id, throw userNotFoundError(id))
        KeystoneUser(user = user)
    }

    private def userByName(token: String, name: String): KeystoneUser = {
        authorize(token)
        val (user, _) = usersByUserName.getOrElse(name, throw userNotFoundError(name))
        KeystoneUser(user = user)
    }

    private def userRoles(token: String, id: String): KeystoneRoles = {
        authorize(token)
        val (user, _) = usersById.getOrElse(id, throw userNotFoundError(id))
        KeystoneRoles(roles = user.roles, roleLinks = null)
    }

    private def tenants(token: String): KeystoneTenants = {
        authorize(token)
        KeystoneTenants(tenants = tenantsById.values.toList.asJava,
                        tenantLinks = null)
    }

    private def tenantById(token: String, id: String): KeystoneTenant = {
        authorize(token)
        KeystoneTenant(tenantsById.getOrElse(id, throw tenantNotFoundError(id)))
    }

    private def tenantByName(token: String, name: String): KeystoneTenant = {
        authorize(token)
        KeystoneTenant(tenantsByName.getOrElse(name, throw tenantNotFoundError(name)))
    }

    private def expireTokens(): Unit = {
        for (entry <- tokens.toList) {
            val expires = KeystoneClient.parseTimestamp(entry._2._2.expires)
            if (expires <= currentTime) {
                tokens -= entry._1
            }
        }
    }

    private def error(code: Int, title: String, message: String)
    : WebApplicationException = {
        val error = KeystoneError(Error(code = code,
                                        title = title,
                                        message = message))
        new WebApplicationException(
            null, Response.status(code).entity(error).`type`(APPLICATION_JSON).build())
    }

    private def unauthorizedError(): WebApplicationException = {
        error(401, "Unauthorized",
              "The request you have made requires authentication.")
    }

    private def tokenNotFoundError(id: String): WebApplicationException = {
        error(404, "Not Found", s"Could not find token: $id")
    }

    private def userNotFoundError(id: String): WebApplicationException = {
        error(404, "Not Found", s"Could not find user: $id")
    }

    private def tenantNotFoundError(id: String): WebApplicationException = {
        error(404, "Not Found", s"Could not find project: $id")
    }

}
