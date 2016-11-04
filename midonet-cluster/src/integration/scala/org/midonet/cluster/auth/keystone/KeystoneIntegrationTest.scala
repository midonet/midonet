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

import com.typesafe.config.ConfigFactory

import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.slf4j.bridge.SLF4JBridgeHandler

import org.midonet.conf.MidoTestConfigurator

/**
 * Provides integration tests for the Keystone v2 client. The tests require
 * a Keystone server with the configuration and at least one administrative
 * tenant as specified below.
 */
class KeystoneIntegrationTest extends FlatSpec with Matchers with GivenWhenThen {

    // Install the SLF4J handler for the legacy loggers used in the API.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    private val keystoneProtocol = "http"
    private val keystoneHost = "127.0.0.1"
    private val keystonePort = 35357
    private val keystoneTenant = "admin"
    private val keystoneDomain = "default"
    private val keystoneUser = "admin"
    private val keystonePassword = "midonet"
    private val keystoneToken = "somelongtesttoken"
    protected val keystoneURL1 = "http://127.0.0.1:" + keystonePort + "/v2.0"
    protected val keystoneURL2 = "http://127.0.0.1:" + keystonePort + "/v2.0"

    private def clientWithoutAdmin(version: Int): KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.version : $version
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
             """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithToken(version: Int): KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.version : $version
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
               |cluster.auth.keystone.admin_token : $keystoneToken
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithPassword(version: Int): KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.version : $version
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
               |cluster.auth.keystone.tenant_name : $keystoneTenant
               |cluster.auth.keystone.domain_name : $keystoneDomain
               |cluster.auth.keystone.user_name : $keystoneUser
               |cluster.auth.keystone.user_password : $keystonePassword
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithURLOverride(version: Int): KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.version : $version
               |cluster.auth.keystone.tenant_name : $keystoneTenant
               |cluster.auth.keystone.user_name : $keystoneUser
               |cluster.auth.keystone.user_password : $keystonePassword
               |cluster.auth.keystone.url : "$keystoneURL1"
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
          .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    "Keystone v2 client" should "handle get version" in {
        val client = clientWithoutAdmin(2)
        val keystoneVersion = client.getVersion
        keystoneVersion.getClass shouldBe classOf[KeystoneVersion]
        keystoneVersion.version.status shouldBe "stable"
        keystoneVersion.version.id shouldBe "v2.0"
    }

    "Keystone v2 client" should "handle authentication with password" in {
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.tokenId should not be null
        auth.r2 should not be null
        auth.r2.token should not be null
        auth.r2.user.name shouldBe keystoneUser
        auth.r3 shouldBe null
        auth.token shouldBe auth.r2.token
        auth.user shouldBe auth.r2.user
        auth.project.name shouldBe keystoneTenant
        auth.roles should not be empty
    }

    "Keystone v2 client" should "handle authentication with token" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Authenticating with the token should succeed")
        auth = client.authenticate(keystoneTenant, auth.tokenId)
        auth.tokenId should not be null
        auth.r2.token should not be null
        auth.r2.user.name shouldBe keystoneUser
        auth.r3 shouldBe null
        auth.token shouldBe auth.r2.token
        auth.user shouldBe auth.r2.user
        auth.project.name shouldBe keystoneTenant
        auth.roles should not be empty
    }

    "Keystone v2 client" should "fail authentication for invalid tenant" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate("no-tenant", keystoneUser, keystonePassword)
        }
    }

    "Keystone v2 client" should "fail authentication for invalid user name" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-user", keystonePassword)
        }
    }

    "Keystone v2 client" should "fail authentication for invalid password" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, keystoneUser, "no-password")
        }
    }

    "Keystone v2 client" should "fail authentication for invalid token" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-token")
        }
    }

    "Keystone v2 client" should "validate a valid token for any tenant with " +
                                "admin token" in {
        Given("A valid token")
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.tokenId,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.token.id should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.tokenId shouldBe auth.token.id
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "validate a valid token for any tenant with " +
                                "admin password" in {
        Given("A valid token")
        val client = clientWithPassword(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.tokenId,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.token.id should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.tokenId shouldBe auth.token.id
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "fail validating a token for any tenant when " +
                                "admin credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            auth = client.validate(auth.tokenId, tenantScope = None)
        }
    }

    "Keystone v2 client" should "validate a valid token for current tenant " +
                                "with admin token" in {
        Given("A valid token")
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.tokenId,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.token.id should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.tokenId shouldBe auth.token.id
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "validate a valid token for current tenant " +
                                "with admin password" in {
        Given("A valid token")
        val client = clientWithPassword(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.tokenId,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.token.id should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.tokenId shouldBe auth.token.id
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "fail validating a token for current tenant " +
                                "when admin credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            auth = client.validate(auth.tokenId, tenantScope = None)
        }
    }

    "Keystone v2 client" should "fail validate a non-existing token with " +
                                "admin token" in {
        Given("A client")
        val client = clientWithToken(2)

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Keystone v2 client" should "fail validate a non-existing token with " +
                                "admin password" in {
        Given("A client")
        val client = clientWithPassword(2)

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Keystone v2 client" should "succeed validate a good token after bad " +
                                "with admin token" in {
        Given("A client")
        val client = clientWithToken(2)

        When("Authenticating a good client")
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating a bad token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }

        And("Validating a good token succeeds")
        val tokenId = auth.tokenId
        auth = client.validate(tokenId, tenantScope = None)
        auth.tokenId shouldBe tokenId
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "succeed validate a good token after bad " +
                                "admin password" in {
        Given("A client")
        val client = clientWithPassword(2)

        When("Authenticating a good client")
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating a bad token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }

        And("Validating a good token succeeds")
        val tokenId = auth.tokenId
        auth = client.validate(tokenId, tenantScope = None)
        auth.tokenId shouldBe tokenId
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "list users" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Listing the users succeeds")
        val keystoneUsers = client.listUsers(auth.tokenId)
        keystoneUsers.users should not be empty
    }

    "Keystone v2 client" should "get user by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name succeeds")
        val userByName = client.getUserByName(keystoneUser, auth.tokenId)
        userByName.user.name shouldBe keystoneUser

        And("Get the user by identifier succeeds")
        val userById = client.getUserById(userByName.user.id, auth.tokenId)
        userById.user.name shouldBe keystoneUser
    }

    "Keystone v2 client" should "fail get non-existing user name" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name fails")
        intercept[KeystoneException] {
            client.getUserByName("some-name", auth.tokenId)
        }
    }

    "Keystone v2 client" should "fail get non-existing user identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getUserById("some-id", auth.tokenId)
        }
    }

    "Keystone v2 client" should "list tenants" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Listing the tenants succeeds")
        val keystoneTenants = client.listTenants(auth.tokenId)
        keystoneTenants.tenants should not be empty
    }

    "Keystone v2 client" should "get tenant by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the tenant by name succeeds")
        val tenantByName = client.getTenantByName(keystoneTenant, auth.tokenId)
        tenantByName.tenant.name shouldBe keystoneTenant

        And("Get the tenant by identifier succeeds")
        val tenantById = client.getTenantById(tenantByName.tenant.id,
                                              auth.tokenId)
        tenantById.tenant.name shouldBe keystoneTenant
    }

    "Keystone v2 client" should "fail get non-existing tenant name" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name fails")
        intercept[KeystoneException] {
            client.getTenantByName("some-name", auth.tokenId)
        }
    }

    "Keystone v2 client" should "fail get non-existing tenant identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getTenantById("some-id", auth.tokenId)
        }
    }

    "Keystone v2 client" should "get tenant user roles" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        When("Get the tenant by name")
        val tenant = client.getTenantByName(keystoneTenant, auth.tokenId)

        And("Get the user by name")
        val user = client.getUserByName(keystoneUser, auth.tokenId)

        Then("Get the tenant user roles succeeds")
        val roles = client.getTenantUserRoles(tenant.tenant.id,
                                              user.user.id, auth.tokenId)
        roles.roles should not be empty
    }

    "Keystone v2 client using URL override" should "list users" in {
        Given("A valid token")
        val client = clientWithURLOverride(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
            keystonePassword)

        Then("Listing the users succeeds")
        val keystoneUsers = client.listUsers(auth.tokenId)
        keystoneUsers.users should not be empty
    }

    "Keystone v3 client" should "handle get version" in {
        val client = clientWithoutAdmin(3)
        val keystoneVersion = client.getVersion
        keystoneVersion.getClass shouldBe classOf[KeystoneVersion]
        keystoneVersion.version.status shouldBe "stable"
        keystoneVersion.version.id shouldBe "v3.4"
    }

    "Keystone v3 client" should "handle authentication with password" in {
        val client = clientWithoutAdmin(3)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.tokenId should not be null
        auth.r3 should not be null
        auth.r3.user.name shouldBe keystoneUser
        auth.r2 shouldBe null
        auth.token shouldBe auth.r3
        auth.user shouldBe auth.r3.user
        auth.project.name shouldBe keystoneTenant
        auth.roles should not be empty
    }

    "Keystone v3 client" should "handle authentication with token" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Authenticating with the token should succeed")
        auth = client.authenticate(keystoneTenant, auth.tokenId)
        auth.tokenId should not be null
        auth.r3 should not be null
        auth.r3.user.name shouldBe keystoneUser
        auth.r2 shouldBe null
        auth.token shouldBe auth.r3
        auth.user shouldBe auth.r3.user
        auth.project.name shouldBe keystoneTenant
        auth.roles should not be empty
    }

    "Keystone v3 client" should "fail authentication for invalid project" in {
        val client = clientWithoutAdmin(3)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate("no-project", keystoneUser, keystonePassword)
        }
    }

    "Keystone v3 client" should "fail authentication for invalid user name" in {
        val client = clientWithoutAdmin(3)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-user", keystonePassword)
        }
    }

    "Keystone v3 client" should "fail authentication for invalid password" in {
        val client = clientWithoutAdmin(3)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, keystoneUser, "no-password")
        }
    }

    "Keystone v3 client" should "fail authentication for invalid token" in {
        val client = clientWithoutAdmin(3)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-token")
        }
    }

    "Keystone v3 client" should "validate a valid token for any project with " +
                                "admin token" in {
        Given("A valid token")
        val client = clientWithToken(3)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.tokenId, tenantScope = None)
        auth.tokenId should not be null
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v3 client" should "validate a valid token for any project with " +
                                "admin password" in {
        Given("A valid token")
        val client = clientWithPassword(3)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        val tokenId = auth.tokenId
        auth = client.validate(tokenId, tenantScope = None)
        auth.tokenId shouldBe tokenId
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v3 client" should "fail validating a token for current tenant " +
                                "when admin credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            auth = client.validate(auth.tokenId, tenantScope = None)
        }
    }

    "Keystone v3 client" should "fail validate a non-existing token with " +
                                "admin token" in {
        Given("A client")
        val client = clientWithToken(3)

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Keystone v3 client" should "fail validate a non-existing token with " +
                                "admin password" in {
        Given("A client")
        val client = clientWithPassword(3)

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Keystone v3 client" should "list projects" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Listing the projects succeeds")
        val keystoneProjects = client.listProjects(auth.tokenId)
        keystoneProjects.projects should not be empty
    }

    "Keystone v3 client" should "get project by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the project by name succeeds")
        val projectByName = client.getProjectByName(keystoneTenant, auth.tokenId)
        projectByName.projects should have size 1

        And("Get the project by identifier succeeds")
        val projectById = client.getProjectById(projectByName.projects.get(0).id,
                                                auth.tokenId)
        projectById.project.name shouldBe keystoneTenant
    }

    "Keystone v3 client" should "fail get non-existing tenant name" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name fails")
        val projectByName = client.getProjectByName("some-name", auth.tokenId)
        projectByName.projects shouldBe empty
    }

    "Keystone v3 client" should "fail get non-existing tenant identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(3)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getProjectById("some-id", auth.tokenId)
        }
    }

    "Keystone v3 client" should "succeed validate a good token after bad " +
                                "with admin token" in {
        Given("A client")
        val client = clientWithToken(3)

        When("Authenticating a good client")
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating a bad token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }

        And("Validating a good token succeeds")
        val tokenId = auth.tokenId
        auth = client.validate(tokenId, tenantScope = None)
        auth.tokenId shouldBe tokenId
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Keystone v3 client" should "succeed validate a good token after bad " +
                                "admin password" in {
        Given("A client")
        val client = clientWithPassword(3)

        When("Authenticating a good client")
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating a bad token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }

        And("Validating a good token succeeds")
        val tokenId = auth.tokenId
        auth = client.validate(tokenId, tenantScope = None)
        auth.tokenId shouldBe tokenId
        auth.token should not be null
        auth.token.issuedAt should not be null
        auth.token.expiresAt should not be null
        auth.user.name shouldBe keystoneUser
    }
}
