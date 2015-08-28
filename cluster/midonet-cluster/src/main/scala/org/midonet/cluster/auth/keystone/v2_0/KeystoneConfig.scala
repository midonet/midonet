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

package org.midonet.cluster.auth.keystone.v2_0

import com.typesafe.config.Config

import org.midonet.cluster.AuthConfig

class KeystoneConfig(conf: Config) extends AuthConfig(conf) {

    def tenantName = conf.getString(s"$Prefix.keystone_v2.tenant_name")
    def adminToken = conf.getString(s"$Prefix.keystone_v2.admin_token")
    def protocol = conf.getString(s"$Prefix.keystone_v2.protocol")
    def host = conf.getString(s"$Prefix.keystone_v2.host")
    def port = conf.getInt(s"$Prefix.keystone_v2.port")

}
