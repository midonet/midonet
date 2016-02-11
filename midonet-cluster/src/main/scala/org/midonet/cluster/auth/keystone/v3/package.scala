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

import java.util.{List => JList, Map => JMap}

import scala.annotation.meta.{param, getter}

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.{JsonIgnore, JsonInclude, JsonProperty, JsonCreator}

import org.midonet.cluster.auth.keystone

package object v3 {

    @JsonInclude(Include.NON_NULL)
    case class Auth @JsonCreator()(
        @(JsonProperty @getter @param)("identity") identity: Identity = null,
        @(JsonProperty @getter @param)("scope") scope: Scope = null)

    case class Catalog @JsonCreator()(
        @(JsonProperty @getter @param)("endpoints") endpoints: JList[Endpoint])

    @JsonInclude(Include.NON_NULL)
    case class Domain @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String = null,
        @(JsonProperty @getter @param)("name") name: String = null)

    case class Endpoint @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String,
        @(JsonProperty @getter @param)("url") url: String,
        @(JsonProperty @getter @param)("region") region: String,
        @(JsonProperty @getter @param)("region_id") region_id: String,
        @(JsonProperty @getter @param)("interface") interface: String)

    @JsonInclude(Include.NON_NULL)
    case class Identity @JsonCreator()(
        @(JsonProperty @getter @param)("methods") methods: JList[String] = null,
        @(JsonProperty @getter @param)("password") password: Password = null,
        @(JsonProperty @getter @param)("token") token: Token = null)

    case class KeystoneAuth @JsonCreator()(
        @(JsonProperty @getter @param)("auth") auth: Auth)


    case class KeystoneProject @JsonCreator()(
        @(JsonProperty @getter @param)("project") project: Project = null)

    case class KeystoneProjects @JsonCreator()(
        @(JsonProperty @getter @param)("links") links: Links = null,
        @(JsonProperty @getter @param)("projects") projects: JList[Project] = null)

    case class KeystoneToken @JsonCreator()(
        @(JsonProperty @getter @param)("token") token: Token)

    case class Links @JsonCreator()(
        @(JsonProperty @param)("next") next: String = null,
        @(JsonProperty @param)("previous") previous: String = null,
        @(JsonProperty @param)("self") self: String = null)

    case class Password @JsonCreator()(
        @(JsonProperty @getter @param)("user") user: User)

    @JsonInclude(Include.NON_NULL)
    case class Project @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String = null,
        @(JsonProperty @getter @param)("name") name: String = null,
        @(JsonProperty @param)("description") description: String = null,
        @(JsonProperty @param)("enabled") enabled: Boolean = false,
        @(JsonProperty @param)("domain_id") domainId: String = null,
        @(JsonProperty @param)("parent_id") parentId: String = null,
        @(JsonProperty @getter @param)("domain") domain: Domain = null,
        @(JsonProperty @param)("is_domain") isDomain: Boolean = false,
        @(JsonProperty @param)("links") links: Links = null)
        extends keystone.Project

    case class Role @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String,
        @(JsonProperty @getter @param)("name") name: String)
        extends keystone.Role

    @JsonInclude(Include.NON_NULL)
    case class Scope @JsonCreator()(
        @(JsonProperty @getter @param)("project") project : Project = null)

    @JsonInclude(Include.NON_NULL)
    case class Token @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String = null,
        @(JsonProperty @getter @param)("methods") methods: JList[String] = null,
        @(JsonProperty @getter @param)("expires_at") expiresAt: String = null,
        @(JsonProperty @getter @param)("issued_at") issuedAt: String = null,
        @(JsonProperty @getter @param)("user") user: User = null,
        @(JsonProperty @getter @param)("audit_ids") auditIds: JList[String] = null,
        @(JsonProperty @getter @param)("extras") extras: JMap[String, String] = null,
        @(JsonProperty @getter @param)("roles") roles: JList[Role] = null,
        @(JsonProperty @getter @param)("project") project: Project = null,
        @(JsonProperty @getter @param)("domain") domain: Domain = null,
        @(JsonProperty @getter @param)("catalog") catalog: JList[Catalog] = null)
        extends keystone.Token

    @JsonInclude(Include.NON_NULL)
    case class User @JsonCreator()(
        @(JsonProperty @getter @param)("id") id: String = null,
        @(JsonProperty @getter @param)("name") name: String = null,
        @(JsonProperty @getter @param)("password") password: String = null,
        @(JsonProperty @param)("enabled") enabled: Boolean = false,
        @(JsonProperty @getter @param)("email") email: String = null,
        @(JsonProperty @getter @param)("domain") domain: Domain = null)
        extends keystone.User

}
