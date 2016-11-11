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
package org.midonet.midolman.haproxy

import scala.sys.process._

class HaproxyHelper(haproxyScript: String) {

    def makens(name: String, iface: String, mac: String, ip: String,
               routerIp: String) = {
        s"$haproxyScript makens $name $iface $mac $ip $routerIp".!!
    }

    def cleanns(name: String, iface: String) = {
        try {
            s"$haproxyScript cleanns $name $iface".!!
        } catch {
            case e: RuntimeException =>
            // expected if the namespace is already cleaned
        }
    }

    def restartHaproxy(name: String, confLocation: String, pidLocation: String) = {
        s"$haproxyScript restart_ha $name $confLocation $pidLocation".!!
    }
}
