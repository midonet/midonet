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

package org.midonet.midolman.openstack.metadata

import scala.sys.process.Process

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.DatapathState

/*
 * Plumbing between VMs and metadata proxy.
 *
 * Datapath flows are installed reactively by MetadataServiceWorkflow.
 */

/*
 * ProxyInfo describes metadata proxy server side of the plumbing.
 * (The other side is described by InstanceInfo.)
 */
case class ProxyInfo(
    val dpPortNo: Int,
    val addr: String,
    val mac: String)

class Plumber(val dpState: DatapathState) {
    private val log: Logger = MetadataService.getLogger

    def plumb(vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo = dpState getDpPortNumberForVport vmInfo.portId
        val addr = AddressManager dpPortToRemoteAddress vmDpPortNo

        log debug s"Plumbing ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}"

        /*
         * TODO(yamamoto): we should set up arp responder
         * for RFC compliant guests and no router case.
         */

        addr
    }

    def unplumb(addr: String, vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo = AddressManager remoteAddressToDpPort addr

        log debug s"Unpluming ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}"
    }
}
