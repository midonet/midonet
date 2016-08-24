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

import java.text.ParsePosition

import com.fasterxml.jackson.databind.util.ISO8601Utils
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, Matchers}

import org.midonet.cluster.auth.keystone.KeystoneTest.DateFormat
import org.midonet.conf.MidoTestConfigurator

@RunWith(classOf[JUnitRunner])
class KeystoneClientTest extends KeystoneTest with Matchers with GivenWhenThen {

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

    "Client" should "handle get version" in {
        val client = clientWithoutAdmin(2)
        val keystoneVersion = client.getVersion
        keystoneVersion.getClass shouldBe classOf[KeystoneVersion]
        keystoneVersion.version.status shouldBe "stable"
        keystoneVersion.version.id shouldBe "v2.0"
    }

    "Client" should "handle authentication with password" in {
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "parse ISO8601 date format UTC" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T00:00:00Z")
        date shouldBe 1451606400000L
    }

    "Client" should "parse ISO8601 date format UTC with microseconds" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T00:00:00.123000Z")
        date shouldBe 1451606400123L
    }

    "Client" should "parse ISO8601 date format no time zone" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T00:00:00")
        date shouldBe 1451606400000L
    }

    "Client" should "parse ISO8601 date format no time zone with microseconds" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T00:00:00.123000")
        date shouldBe 1451606400123L
    }

    "Client" should "parse ISO8601 date format time zone" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T10:00:00+10:00")
        date shouldBe 1451606400000L
    }

    "Client" should "parse ISO8601 date format time zone with microseconds" in {
        val date = KeystoneClient.parseTimestamp("2016-01-01T10:00:00.123000+10:00")
        date shouldBe 1451606400123L
    }

    "Client" should "handle ISO8601 date format" in {
        dateFormat = DateFormat.Iso8601
        currentTime = ISO8601Utils.parse("2016-01-01T00:00:00Z",
                                         new ParsePosition(0)).getTime
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.token should not be null
        auth.token.issuedAt shouldBe "2016-01-01T00:00:00Z"
        auth.token.expiresAt shouldBe "2016-01-01T00:30:00Z"
    }

    "Client" should "handle ISO8601 date format for Fernet tokens" in {
        dateFormat = DateFormat.Iso8601Micro
        currentTime = ISO8601Utils.parse("2016-01-01T00:00:00Z",
                                         new ParsePosition(0)).getTime
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.token should not be null
        auth.token.issuedAt shouldBe "2016-01-01T00:00:00.000000Z"
        auth.token.expiresAt shouldBe "2016-01-01T00:30:00.000000Z"
    }

    "Client" should "handle tokens that never expire" in {
        tokenNeverExpires = true
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)
        auth.token should not be null
        auth.token.expiresAt shouldBe null

        auth = client.validate(auth.token.id, tenantScope = None)
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
        tokenNeverExpires = false
    }

    "Client" should "handle authentication with token" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Authenticating with the token should succeed")
        auth = client.authenticate(keystoneTenant, auth.token.id)
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "fail authentication for invalid tenant" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate("no-tenant", keystoneUser, keystonePassword)
        }
    }

    "Client" should "fail authentication for invalid user name" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-user", keystonePassword)
        }
    }

    "Client" should "fail authentication for invalid password" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, keystoneUser, "no-password")
        }
    }

    "Client" should "fail authentication for invalid token" in {
        val client = clientWithoutAdmin(2)
        intercept[KeystoneUnauthorizedException] {
            client.authenticate(keystoneTenant, "no-token")
        }
    }

    "Client" should "validate a valid token for any tenant with admin token" in {
        Given("A valid token")
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.token.id, tenantScope = None)
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "validate a valid token for any tenant with admin password" in {
        Given("A valid token")
        val client = clientWithPassword(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.token.id, tenantScope = None)
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "fail validating a token for any tenant when admin " +
                    "credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            auth = client.validate(auth.token.id, tenantScope = None)
        }
    }

    "Client" should "validate a valid token for current tenant with admin " +
                    "token" in {
        Given("A valid token")
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.token.id,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "validate a valid token for current tenant with admin " +
                    "password" in {
        Given("A valid token")
        val client = clientWithPassword(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.token.id,
                               tenantScope = Some(auth.r2.token.tenant.id))
        auth.token should not be null
        auth.user.name shouldBe keystoneUser
    }

    "Client" should "fail validating a token for current tenant when admin" +
                    " credentials not set" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            auth = client.validate(auth.token.id, tenantScope = None)
        }
    }

    "Client" should "fail validate a non-existing token with admin token" in {
        Given("A valid token")
        val client = clientWithToken(2)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Client" should "fail validate a non-existing token with admin password" in {
        Given("A client")
        val client = clientWithPassword(2)

        Then("Validating the token fails")
        intercept[KeystoneException] {
            client.validate("invalid-token", tenantScope = None)
        }
    }

    "Client" should "fail validate an expired token obtained with admin token" in {
        Given("A valid token")
        val client = clientWithToken(2)
        var auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Validating the token succeeds")
        auth = client.validate(auth.token.id,
                               tenantScope = Some(auth.r2.token.tenant.id))

        When("The token expires")
        currentTime = currentTime + tokenLifetime

        Then("Validating the token fails")
        intercept[KeystoneException] {
            client.validate(auth.token.id, tenantScope = None)
        }
    }

    "Client" should "list users" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Listing the users succeeds")
        val keystoneUsers = client.listUsers(auth.token.id)
        keystoneUsers.users should not be empty
    }


    "Client" should "get user by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name succeeds")
        val userByName = client.getUserByName(keystoneUser,
                                              auth.token.id)
        userByName.user.name shouldBe keystoneUser

        And("Get the user by identifier succeeds")
        val userById = client.getUserById(userByName.user.id, auth.token.id)
        userById.user.name shouldBe keystoneUser
    }

    "Client" should "fail get non-existing user name" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by name fails")
        intercept[KeystoneException] {
            client.getUserByName("some-name", auth.token.id)
        }
    }

    "Client" should "fail get non-existing user identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the user by identifier fails")
        intercept[KeystoneException] {
            client.getUserById("some-id", auth.token.id)
        }
    }

    "Client" should "list tenants" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Listing the tenants succeeds")
        val keystoneTenants = client.listTenants(auth.token.id)
        keystoneTenants.tenants should not be empty
    }

    "Client" should "get tenant by name and by identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the tenant by name succeeds")
        val tenantByName = client.getTenantByName(keystoneTenant,
                                                  auth.token.id)
        tenantByName.tenant.name shouldBe keystoneTenant

        And("Get the tenant by identifier succeeds")
        val tenantById = client.getTenantById(tenantByName.tenant.id,
                                              auth.token.id)
        tenantById.tenant.name shouldBe keystoneTenant
    }

    "Client" should "fail get non-existing tenant name" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the tenant by name fails")
        intercept[KeystoneException] {
            client.getTenantByName("some-name", auth.token.id)
        }
    }

    "Client" should "fail get non-existing tenant identifier" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        Then("Get the tenant by identifier fails")
        intercept[KeystoneException] {
            client.getTenantById("some-id", auth.token.id)
        }

    }

    "Client" should "get tenant user roles" in {
        Given("A valid token")
        val client = clientWithoutAdmin(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
                                       keystonePassword)

        When("Get the tenant by name")
        val tenant = client.getTenantByName(keystoneTenant, auth.token.id)

        And("Get the user by name")
        val user = client.getUserByName(keystoneUser, auth.token.id)

        Then("Get the tenant user roles succeeds")
        val roles = client.getTenantUserRoles(tenant.tenant.id, user.user.id,
                                              auth.token.id)
        roles.roles should not be empty
    }

    "Client with URL override" should "list users" in {
        Given("A valid token")
        val client = clientWithURLOverride(2)
        val auth = client.authenticate(keystoneTenant, keystoneUser,
            keystonePassword)

        Then("Listing the users succeeds")
        val keystoneUsers = client.listUsers(auth.token.id)
        keystoneUsers.users should not be empty
    }
}
