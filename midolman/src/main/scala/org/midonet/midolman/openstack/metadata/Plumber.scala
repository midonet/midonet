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
MD_PORT=7
MD_IP=169.254.169.254
MD_MAC=7e:a0:49:f4:5e:b7
VM_PORT=6
VM_MAC=fa:16:3e:d0:39:ca
VM_FIXED_IP=10.0.0.3
VM_MAPPED_IP=169.254.1.1
*/

case class ProxyInfo(
    val dpPortNo: Int,
    val addr: String,
    val mac: String)

class Plumber(val injector: Injector) {
    private val log: Logger = LoggerFactory.getLogger(classOf[Plumber])
    private val writer = injector.getInstance(classOf[FlowWriter])

    def write(keys: ArrayList[FlowKey], actions: ArrayList[FlowAction]) = {
        val zeros = ByteBuffer.allocate(100)
        val mch = new FlowMatch
        val mask = new FlowMask

        /*
         * XXX we need to roll our own as calculateFor doesn't take care
         * of TcpFlags etc
         */
        mch.addKeys(keys)
        mch.getEtherType
        mch.getNetworkProto
        mch.getNetworkDstIP
        mch.getIpFragmentType
        mask.calculateFor(mch)
        mask.use(zeros, OvsAttr.TCP)
        mask.use(zeros, OvsAttr.TcpFlags)
        writer.write(keys, actions, mask)
    }

    def egressKeys(vmDpPortNo: Int,
                   vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val keys = new ArrayList[FlowKey]
        val zero_mac = ByteBuffer.allocate(6).array

        keys.add(FlowKeys.inPort(vmDpPortNo))
        keys.add(FlowKeys.ethernet(zero_mac, zero_mac))
        keys.add(FlowKeys.etherType(0x0800.toShort))
        keys.add(FlowKeys.ipv4(IPv4Addr(vmInfo.addr),
                               IPv4Addr(mdInfo.addr),
                               TCP.PROTOCOL_NUMBER,
                               0.toByte, 0.toByte,
                               IPFragmentType.None))
        keys.add(FlowKeys.tcp(0.toShort, 0.toShort))
        keys.add(FlowKeys.tcpFlags(0.toShort))
        keys
    }

    def ingressKeys(addr: String,
                    vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val keys = new ArrayList[FlowKey]
        val zero_mac = ByteBuffer.allocate(6).array

        keys.add(FlowKeys.inPort(mdInfo.dpPortNo))
        keys.add(FlowKeys.ethernet(zero_mac, zero_mac))
        keys.add(FlowKeys.etherType(0x0800.toShort))
        keys.add(FlowKeys.ipv4(IPv4Addr(mdInfo.addr),
                               IPv4Addr(addr),
                               TCP.PROTOCOL_NUMBER,
                               0.toByte, 0.toByte,
                               IPFragmentType.None))
        keys.add(FlowKeys.tcp(0.toShort, 0.toShort))
        keys.add(FlowKeys.tcpFlags(0.toShort))
        keys
    }

    def plumb(vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo =
            writer.dpState getDpPortNumberForVport vmInfo.portId
        val addr = AddressManager.getRemoteAddress(vmDpPortNo)
        val actions = new ArrayList[FlowAction]

        log.info(s"Plumbing ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}")

        /*
        ./ovs-dpctl add-flow midonet "in_port(${VM_PORT}),eth(),eth_type(0x0800),ipv4(src=${VM_FIXED_IP},dst=${MD_IP},proto=6,frag=no),tcp(),tcp_flags(0/0)" "set(eth(src=${VM_MAC},dst={MD_MAC})),set(ipv4(src=${VM_MAPPED_IP},dst=${MD_IP},proto=6,frag=no,tos=0,ttl=10)),${MD_PORT}"
        */

        val outKeys = egressKeys(vmDpPortNo, vmInfo, mdInfo)

        actions.add(FlowActions.setKey(FlowKeys.ethernet(
            MAC.stringToBytes(vmInfo.mac),
            MAC.stringToBytes(mdInfo.mac))))
        actions.add(FlowActions.setKey(FlowKeys.ipv4(
            IPv4Addr(addr),
            IPv4Addr(mdInfo.addr),
            TCP.PROTOCOL_NUMBER,
            0.toByte, 0.toByte,
            IPFragmentType.None)))
        actions.add(FlowActions.output(mdInfo.dpPortNo))
        write(outKeys, actions)

        /*
        ./ovs-dpctl add-flow midonet "in_port(${MD_PORT}),eth(),eth_type(0x0800),ipv4(src=${MD_IP},dst=${VM_MAPPED_IP},proto=6,frag=no),tcp(),tcp_flags(0/0)" "set(eth(src=${MD_MAC},dst=${VM_MAC})),set(ipv4(src=169.254.169.254,dst=${VM_FIXED_IP},proto=6,frag=no,tos=0,ttl=10)),${VM_PORT}"
         */
        val inKeys = ingressKeys(addr, vmInfo, mdInfo)

        actions.clear
        actions.add(FlowActions.setKey(FlowKeys.ethernet(
            MAC.stringToBytes(mdInfo.mac),
            MAC.stringToBytes(vmInfo.mac))))
        actions.add(FlowActions.setKey(FlowKeys.ipv4(
            IPv4Addr(mdInfo.addr),
            IPv4Addr(vmInfo.addr),
            TCP.PROTOCOL_NUMBER,
            0.toByte, 0.toByte,
            IPFragmentType.None)))
        actions.add(FlowActions.output(vmDpPortNo))
        write(inKeys, actions)

        // install a static arp entry for the vm.
        // any mac address is ok.
        Process(s"arp -s ${addr} 11:11:11:11:11").!

        addr
    }

    def unplumb(addr: String, vmInfo: InstanceInfo, mdInfo: ProxyInfo) = {
        val vmDpPortNo = AddressManager.putRemoteAddress(addr)

        log.info(s"Unpluming ${vmDpPortNo} ${addr} ${vmInfo} ${mdInfo}")

        writer.delete(egressKeys(vmDpPortNo, vmInfo, mdInfo))
        writer.delete(ingressKeys(addr, vmInfo, mdInfo))
    }
}
