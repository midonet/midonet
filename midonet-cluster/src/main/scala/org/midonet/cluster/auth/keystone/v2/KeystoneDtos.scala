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

import java.util.{List => JList}

import scala.annotation.meta.{param, getter}

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonInclude, JsonCreator, JsonProperty}

case class Access @JsonCreator()(
    @(JsonProperty @getter @param)("token") token: Token,
    @(JsonProperty @getter @param)("serviceCatalog") serviceCatalog: JList[ServiceEntry],
    @(JsonProperty @getter @param)("user") user: User,
    @(JsonProperty @getter @param)("metadata") metadata: Metadata,
    @(JsonProperty @getter @param)("trust") trust: Trust)

trait Auth

case class Endpoint @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("adminURL") adminUrl: String,
    @(JsonProperty @getter @param)("internalURL") internalUrl: String,
    @(JsonProperty @getter @param)("publicURL") publicUrl: String,
    @(JsonProperty @getter @param)("region") region: String)

case class KeystoneAccess @JsonCreator()(
    @(JsonProperty @getter @param)("access") access: Access)

case class KeystoneAuth @JsonCreator()(
    @(JsonProperty @getter @param)("auth") auth: Auth)

case class KeystoneRoles @JsonCreator()(
    @(JsonProperty @getter @param)("roles") roles: JList[Role],
    @(JsonProperty @getter @param)("role_links") roleLinks: JList[Link])

case class KeystoneTenant @JsonCreator()(
    @(JsonProperty @getter @param)("tenant") tenant: Tenant)

case class KeystoneTenants @JsonCreator()(
    @(JsonProperty @getter @param)("tenants") tenants: JList[Tenant],
    @(JsonProperty @getter @param)("tenant_links") tenantLinks: JList[Link])

case class KeystoneUser @JsonCreator()(
    @(JsonProperty @getter @param)("user") user: User)

case class KeystoneUsers @JsonCreator()(
    @(JsonProperty @getter @param)("users") users: JList[User])

case class KeystoneVersion @JsonCreator()(
    @(JsonProperty @getter @param)("version") version: Version)

case class Link @JsonCreator()(
    @(JsonProperty @getter @param)("href") href: String,
    @(JsonProperty @getter @param)("rel") rel: String,
    @(JsonProperty @getter @param)("type") `type`: String)

case class MediaType @JsonCreator()(
    @(JsonProperty @getter @param)("base") base: String,
    @(JsonProperty @getter @param)("type") `type`: String)

case class Metadata @JsonCreator()(
    @(JsonProperty @getter @param)("is_admin") isAdmin: Boolean,
    @(JsonProperty @getter @param)("roles") roles: JList[String])

case class PasswordAuth @JsonCreator()(
    @(JsonProperty @getter @param)("passwordCredentials") credentials: PasswordCredentials,
    @(JsonProperty @getter @param)("tenantName") tenantName: String)
    extends Auth

case class PasswordCredentials @JsonCreator()(
    @(JsonProperty @getter @param)("username") userName: String,
    @(JsonProperty @getter @param)("password") password: String)

case class Role @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("name") name: String,
    @(JsonProperty @getter @param)("description") description: String)

case class ServiceEntry @JsonCreator()(
    @(JsonProperty @getter @param)("name") name: String,
    @(JsonProperty @getter @param)("type") `type`: String,
    @(JsonProperty @getter @param)("endpoints") endpoints: JList[Endpoint],
    @(JsonProperty @getter @param)("endpoint_links") endpointLinks: JList[String])

case class Tenant @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("enabled") enabled: Boolean,
    @(JsonProperty @getter @param)("name") name: String,
    @(JsonProperty @getter @param)("description") description: String)

@JsonInclude(Include.NON_NULL)
case class Token @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("issued_at") issuedAt: String = null,
    @(JsonProperty @getter @param)("expires") expires: String = null,
    @(JsonProperty @getter @param)("tenant") tenant: Tenant = null,
    @(JsonProperty @getter @param)("audit_ids") auditIds: JList[String] = null)

case class TokenAuth @JsonCreator()(
    @(JsonProperty @getter @param)("token") token: Token,
    @(JsonProperty @getter @param)("tenantName") tenantName: String)
    extends Auth

case class Trust @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("trustee_user_id") trusteeUserId: String,
    @(JsonProperty @getter @param)("trustor_user_id") trustorUserId: String,
    @(JsonProperty @getter @param)("impersonation") impersonation: Boolean)

case class User @JsonCreator()(
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("username") userName: String,
    @(JsonProperty @getter @param)("name") name: String,
    @(JsonProperty @getter @param)("enabled") enabled: Boolean,
    @(JsonProperty @getter @param)("email") email: String,
    @(JsonProperty @getter @param)("roles") roles: JList[Role],
    @(JsonProperty @getter @param)("role_links") roleLinks: JList[String])

case class Version @JsonCreator()(
    @(JsonProperty @getter @param)("status") status: String,
    @(JsonProperty @getter @param)("updated") updated: String,
    @(JsonProperty @getter @param)("media-types") mediaTypes: JList[MediaType],
    @(JsonProperty @getter @param)("id") id: String,
    @(JsonProperty @getter @param)("links") links: JList[Link])
