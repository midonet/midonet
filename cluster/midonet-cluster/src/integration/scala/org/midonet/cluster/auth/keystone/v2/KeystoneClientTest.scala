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
class KeystoneClientTest extends FlatSpec with Matchers with GivenWhenThen {

    // Install the SLF4J handler for the legacy loggers used in the API.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()

    private val keystoneProtocol = "http"
    private val keystoneHost = "127.0.0.1"
    private val keystonePort = 35357
    private val keystoneTenant = "admin"
    private val keystoneUserName = "admin"
    private val keystonePassword = "midonet"
    private val keystoneToken = "ADMIN"

    private def clientWithoutAdmin: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone_v2.protocol : $keystoneProtocol
               |cluster.auth.keystone_v2.host : $keystoneHost
               |cluster.auth.keystone_v2.port : $keystonePort
             """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithToken: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone_v2.protocol : $keystoneProtocol
               |cluster.auth.keystone_v2.host : $keystoneHost
               |cluster.auth.keystone_v2.port : $keystonePort
               |cluster.auth.keystone_v2.admin_token : $keystoneToken
            """.stripMargin
        val config = ConfigFactory.parseString(configStr)
            .withFallback(MidoTestConfigurator.forClusters())
        new KeystoneClient(new KeystoneConfig(config))
    }

    private def clientWithPassword: KeystoneClient = {
        val configStr =
            s"""
               |cluster.auth.keystone_v2.protocol : $keystoneProtocol
               |cluster.auth.keystone_v2.host : $keystoneHost
               |cluster.auth.keystone_v2.port : $keystonePort
               |cluster.auth.keystone_v2.tenant_name : $keystoneTenant
               |cluster.auth.keystone_v2.user_name : $keystoneUserName
               |cluster.auth.keystone_v2.password : $keystonePassword
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
                                                 keystoneUserName,
                                                 keystonePassword)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "handle authentication with token" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Authenticating with the token should succeed")
        keystoneAccess = client.authenticate(keystoneTenant,
                                             keystoneAccess.access.token.id)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "fail authentication for invalid tenant" in {
        val client = clientWithoutAdmin
        intercept[KeystoneUnauthorizedException] {
            client.authenticate("no-tenant", keystoneUserName, keystonePassword)
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
            client.authenticate(keystoneTenant, keystoneUserName, "no-password")
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
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = false)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "validate a valid token for any tenant with admin password" in {
        Given("A valid token")
        val client = clientWithPassword
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = false)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "fail validating a token for any tenant when admin " +
                    "credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
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
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = true)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "validate a valid token for current tenant with admin " +
                    "password" in {
        Given("A valid token")
        val client = clientWithPassword
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Validating the token succeeds")
        keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                         tenantScope = true)
        keystoneAccess.access.token should not be null
        keystoneAccess.access.user.userName shouldBe keystoneUserName
    }

    "Client" should "fail validating a token for current tenant when admin" +
                    " credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        var keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            keystoneAccess = client.validate(keystoneAccess.access.token.id,
                                             tenantScope = true)
        }
    }

    "Client" should "list tenants" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
                                                 keystonePassword)

        Then("Listing the tenants succeeds")
        val keystoneTenants = client.listTenants(keystoneAccess.access.token.id)
        keystoneTenants.tenants should not be empty
    }

    "Client" should "get tenant by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin
        val keystoneAccess = client.authenticate(keystoneTenant,
                                                 keystoneUserName,
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

}
