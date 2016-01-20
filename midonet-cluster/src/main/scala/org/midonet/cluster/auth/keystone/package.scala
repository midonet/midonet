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

package org.midonet.cluster.auth

import java.util.{List => JList, Collections}

import javax.annotation.Nullable

import scala.annotation.meta.{getter, param}
import scala.util.control.NonFatal

import com.fasterxml.jackson.annotation.{JsonCreator, JsonProperty}

package object keystone {

    case class AuthResponse(tokenId: String,
                            r2: v2.Access = null, r3: v3.Token = null) {
        @Nullable def token: Token = {
            withVersion(r2.token, r3, null)
        }
        @Nullable def user: User = {
            withVersion(r2.user, r3.user, null)
        }
        @Nullable def project: Project = {
            withVersion(r2.token.tenant, r3.project, null)
        }
        def roles: JList[Role] = {
            withVersion[JList[Role]](r2.user.roles.asInstanceOf[JList[Role]],
                                     r3.roles.asInstanceOf[JList[Role]],
                                     Collections.emptyList[Role])
        }

        private def withVersion[R](f2: => R, f3: => R, default: R): R = {
            try {
                if (r2 ne null) f2
                else if (r3 ne null) f3
                else default
            } catch {
                case NonFatal(_) => default
            }
        }
    }

    case class KeystoneVersion @JsonCreator()(
        @(JsonProperty @getter @param)("version") version: Version)

    case class Link @JsonCreator()(
        @(JsonProperty @getter @param)("href") href: String,
        @(JsonProperty @getter @param)("rel") rel: String,
        @(JsonProperty @getter @param)("type") `type`: String)

    case class MediaType @JsonCreator()(
        @(JsonProperty @getter @param)("base") base: String,
        @(JsonProperty @getter @param)("type") `type`: String)

    trait Project {
        def id: String
        def name: String
        def enabled: Boolean
    }

    trait Role {
        def id: String
        def name: String
    }

    trait Token {
        def id: String
        def issuedAt: String
        def expiresAt: String
    }

    trait User {
        def id: String
        def name: String
        def enabled: Boolean
        def email: String
    }

    case class Version @JsonCreator()(
        @(JsonProperty @getter @param)("status") status: String,
        @(JsonProperty @getter @param)("updated") updated: String,
        @(JsonProperty @getter @param)("media-types") mediaTypes: JList[MediaType],
        @(JsonProperty @getter @param)("id") id: String,
        @(JsonProperty @getter @param)("links") links: JList[Link])

}
