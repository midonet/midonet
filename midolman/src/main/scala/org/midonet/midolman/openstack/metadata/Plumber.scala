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

import java.nio.ByteBuffer
import java.util.ArrayList
import scala.sys.process.Process

import com.google.inject.Injector
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.odp.flows.FlowAction
import org.midonet.odp.flows.FlowActions
import org.midonet.odp.flows.FlowKey
import org.midonet.odp.flows.FlowKeys
import org.midonet.odp.FlowMask
import org.midonet.odp.FlowMatch
import org.midonet.odp.OpenVSwitch.FlowKey.{Attr => OvsAttr}
import org.midonet.packets.IPFragmentType
import org.midonet.packets.IPv4Addr
import org.midonet.packets.MAC
import org.midonet.packets.TCP

/*
 * Plumbing between VMs and metadata proxy.
 *
 * XXX Should update flows on fixed_ips update.
 */

case class ProxyInfo(
    val dpPortNo: Int,
    val addr: String,
    val mac: String)

class Plumber(val injector: Injector) {
    private val log: Logger = LoggerFactory getLogger classOf[Plumber]
    private val writer = injector getInstance classOf[FlowWriter]

    def write(keys: ArrayList[FlowKey], actions: ArrayList[FlowAction]) = {
        val mch = new FlowMatch
        val mask = new FlowMask

        mch addKeys keys
        mch.getEtherType
        mch.getNetworkProto
        mch.getNetworkDstIP
        mch.getIpFragmentType
        mask calculateFor mch

        /*
         * NOTE(yamamoto): The following all-0 masks are workarounds for
         * the lack of masked-set actions.  Revisit later.
         *
         * - We want to rewrite IP address.
         * - In order to rewrite IP address, we need to rewrite the whole
         *   IP header, including ip_proto.
         * - As we rewrite ip_proto, we want to match on it.  ie. ip_proto=TCP
         * - While we actually don't care TCP header, given ip_proto=TCP,
         *   the kernel datapath complains (EINVAL) if it doesn't exist.
         */
        val zeros = ByteBuffer allocate 32

        mask.use(zeros, OvsAttr.TCP)
        mask.use(zeros, OvsAttr.TcpFlags)
        writer.write(keys, actions, mask)
    }

    def egressKeys(vmDpPortNo: Int,
                   vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val keys = new ArrayList[FlowKey]
        val zero_mac = ByteBuffer.allocate(6).array

        keys add FlowKeys.inPort(vmDpPortNo)
        keys add FlowKeys.ethernet(zero_mac, zero_mac)
        keys add FlowKeys.etherType(0x0800.toShort)
        keys add FlowKeys.ipv4(IPv4Addr(vmInfo.addr),
                               IPv4Addr(mdInfo.addr),
                               TCP.PROTOCOL_NUMBER,
                               0.toByte, 0.toByte,
                               IPFragmentType.None)
        keys add FlowKeys.tcp(0.toShort, 0.toShort)
        keys add FlowKeys.tcpFlags(0.toShort)
        keys
    }

    def ingressKeys(addr: String,
                    vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val keys = new ArrayList[FlowKey]
        val zero_mac = ByteBuffer.allocate(6).array

        keys add FlowKeys.inPort(mdInfo.dpPortNo)
        keys add FlowKeys.ethernet(zero_mac, zero_mac)
        keys add FlowKeys.etherType(0x0800.toShort)
        keys add FlowKeys.ipv4(IPv4Addr(mdInfo.addr),
                               IPv4Addr(addr),
                               TCP.PROTOCOL_NUMBER,
                               0.toByte, 0.toByte,
                               IPFragmentType.None)
        keys add FlowKeys.tcp(0.toShort, 0.toShort)
        keys add FlowKeys.tcpFlags(0.toShort)
        keys
    }

    def plumb(vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo =
            writer.dpState getDpPortNumberForVport vmInfo.portId
        val addr = AddressManager getRemoteAddress vmDpPortNo
        val actions = new ArrayList[FlowAction]

        log debug s"Plumbing ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}"

        val outKeys = egressKeys(vmDpPortNo, vmInfo, mdInfo)

        actions add FlowActions.setKey(FlowKeys.ethernet(
            MAC.stringToBytes(vmInfo.mac),
            MAC.stringToBytes(mdInfo.mac)))
        actions add FlowActions.setKey(FlowKeys.ipv4(
            IPv4Addr(addr),
            IPv4Addr(mdInfo.addr),
            TCP.PROTOCOL_NUMBER,
            0.toByte, 0.toByte,
            IPFragmentType.None))
        actions add FlowActions.output(mdInfo.dpPortNo)
        write(outKeys, actions)

        val inKeys = ingressKeys(addr, vmInfo, mdInfo)

        actions.clear
        actions add FlowActions.setKey(FlowKeys.ethernet(
            MAC.stringToBytes(mdInfo.mac),
            MAC.stringToBytes(vmInfo.mac)))
        actions add FlowActions.setKey(FlowKeys.ipv4(
            IPv4Addr(mdInfo.addr),
            IPv4Addr(vmInfo.addr),
            TCP.PROTOCOL_NUMBER,
            0.toByte, 0.toByte,
            IPFragmentType.None))
        actions add FlowActions.output(vmDpPortNo)
        write(inKeys, actions)

        /*
         * Install a static ARP entry for the VM.
         * The specific MAC address here doesn't actually matter because
         * it's always overwritten by our flows anyway.
         * XXX better to use rtnetlink
         */
        Process(s"arp -s ${addr} ${vmInfo.mac}").!

        addr
    }

    def unplumb(addr: String, vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo = AddressManager putRemoteAddress addr

        log debug s"Unpluming ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}"

        writer delete egressKeys(vmDpPortNo, vmInfo, mdInfo)
        writer delete ingressKeys(addr, vmInfo, mdInfo)

        /*
         * XXX better to use rtnetlink
         */
        Process(s"arp -d ${addr}").!
    }
}
