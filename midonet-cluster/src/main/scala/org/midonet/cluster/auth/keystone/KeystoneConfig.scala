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

import com.typesafe.config.Config

import org.midonet.cluster.AuthConfig

class KeystoneConfig(conf: Config) extends AuthConfig(conf) {

    def version = conf.getInt(s"$prefix.keystone.version")
    def projectName = conf.getString(s"$prefix.keystone.tenant_name")
    def domainName = conf.getString(s"$prefix.keystone.domain_name")
    def domainId = conf.getString(s"$prefix.keystone.domain_id")
    def userName = conf.getString(s"$prefix.keystone.user_name")
    def password = conf.getString(s"$prefix.keystone.user_password")
    def adminToken = conf.getString(s"$prefix.keystone.admin_token")
    def protocol = conf.getString(s"$prefix.keystone.protocol")
    def host = conf.getString(s"$prefix.keystone.host")
    def port = conf.getInt(s"$prefix.keystone.port")

}
