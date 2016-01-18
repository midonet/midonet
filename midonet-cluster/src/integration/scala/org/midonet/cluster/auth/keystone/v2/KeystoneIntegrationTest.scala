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

package org.midonet.cluster.auth.keystone.v2

import com.typesafe.config.ConfigFactory

import org.scalatest.{GivenWhenThen, FlatSpec, Matchers}
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
    private val keystoneUser = "admin"
    private val keystonePassword = "midonet"
    private val keystoneToken = "somelongtesttoken"

    private def clientWithoutAdmin: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
             """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithToken: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
               |cluster.auth.keystone.admin_token : $keystoneToken
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithPassword: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone.protocol : $keystoneProtocol
               |cluster.auth.keystone.host : $keystoneHost
               |cluster.auth.keystone.port : $keystonePort
               |cluster.auth.keystone.tenant_name : $keystoneTenant
               |cluster.auth.keystone.user_name : $keystoneUser
               |cluster.auth.keystone.user_password : $keystonePassword
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    "Client" should "handle get version" in {
        val client = clientWithoutAdmin
        val keystoneVersion = client.getVersion
        keystoneVersion.getClass shouldBe classOf[KeystoneVersion]
        keystoneVersion.version.status shouldBe "stable"
        keystoneVersion.version.id shouldBe "v2.0"
    }

    "Client" should "handle authentication with password" in {
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "handle authentication with token" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Authenticating with the token should succeed")
        keystoneAccess = client.authenticate(keystoneTenant,
                                             keystoneAccess.access.token.id)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "fail authentication for invalid tenant" in {
        val client = clientWithoutAdmin
        intercept[KeystoneUnauthorizedException] {
            client.authenticate("no-tenant", keystoneUser, keystonePassword)
        }
    }

    "Client" should "fail authentication for invalid user name" in {
        val client = clientWithoutAdmin
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-user", keystonePassword)
        }
    }

    "Client" should "fail authentication for invalid password" in {
        val client = clientWithoutAdmin
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, keystoneUser, "no-password")
        }
    }

    "Client" should "fail authentication for invalid token" in {
        val client = clientWithoutAdmin
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-token")
        }
    }

    "Client" should "validate a valid token for any tenant with admin token" in {
        Given("A valid token")
        val client = clientWithToken
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = false)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "validate a valid token for any tenant with admin password" in {
        Given("A valid token")
        val client = clientWithPassword
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = false)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "fail validating a token for any tenant when admin " +
                    "credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                             tenantScope = false)
        }
    }

    "Client" should "validate a valid token for current tenant with admin " +
                    "token" in {
        Given("A valid token")
        val client = clientWithToken
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = true)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "validate a valid token for current tenant with admin " +
                    "password" in {
        Given("A valid token")
        val client = clientWithPassword
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = true)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUser
    }

    "Client" should "fail validating a token for current tenant when admin" +
                    " credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                             tenantScope = true)
        }
    }

    "Client" should "fail validate a non-existing token with admin token" in {
        Given("A client")
        val client = clientWithToken

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = false)
        }
    }

    "Client" should "fail validate a non-existing token with admin password" in {
        Given("A client")
        val client = clientWithPassword

        Then("Validating the token succeeds")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = false)
        }
    }

    "Client" should "list users" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Listing the users succeeds")
        val keystoneUsers = client.listUsers(keystoneAccess.access.token.id)
        keystoneUsers.users should not be empty
    }

    "Client" should "get user by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the user by name succeeds")
        val userByName = client.getUserByName(keystoneUser,
                                              keystoneAccess.access.token.id)
        userByName.user.name shouldBe keystoneUser

        And("Get the user by identifier succeeds")
        val userById = client.getUserById(userByName.user.id,
                                          keystoneAccess.access.token.id)
        userById.user.name shouldBe keystoneUser
    }

    "Client" should "fail get non-existing user name" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the user by name fails")
        intercept[KeystoneException] {
            client.getUserByName("some-name", keystoneAccess.access.token.id)
        }
    }

    "Client" should "fail get non-existing user identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getUserById("some-id", keystoneAccess.access.token.id)
        }
    }

    "Client" should "list tenants" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Listing the tenants succeeds")
        val keystoneTenants = client.listTenants(keystoneAccess.access.token.id)
        keystoneTenants.tenants should not be empty
    }

    "Client" should "get tenant by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the tenant by name succeeds")
        val tenantByName = client.getTenantByName(keystoneTenant,
                                                  keystoneAccess.access.token.id)
        tenantByName.tenant.name shouldBe keystoneTenant

        And("Get the tenant by identifier succeeds")
        val tenantById = client.getTenantById(tenantByName.tenant.id,
                                              keystoneAccess.access.token.id)
        tenantById.tenant.name shouldBe keystoneTenant
    }


    "Client" should "fail get non-existing tenant name" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the user by name fails")
        intercept[KeystoneException] {
            client.getTenantByName("some-name", keystoneAccess.access.token.id)
        }
    }

    "Client" should "fail get non-existing tenant identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getTenantById("some-id", keystoneAccess.access.token.id)
        }
    }

    "Client" should "get tenant user roles" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUser,
                                                 keystonePassword)

        When("Get the tenant by name")
        val tenant = client.getTenantByName(keystoneTenant,
                                            keystoneAccess.access.token.id)

        And("Get the user by name")
        val user = client.getUserByName(keystoneUser,
                                        keystoneAccess.access.token.id)

        Then("Get the tenant user roles succeeds")
        val roles = client.getTenantUserRoles(tenant.tenant.id,
                                              user.user.id,
                                              keystoneAccess.access.token.id)
        roles.roles should not be empty
    }

}
