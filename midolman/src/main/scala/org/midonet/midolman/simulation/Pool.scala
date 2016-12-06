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

package org.midonet.midolman.simulation

import java.util
import java.util.{Objects, UUID}

import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.l4lb.{PoolLBMethod, SessionPersistence}
import org.midonet.midolman.topology.VirtualTopology.VirtualDevice
import org.midonet.packets.{ICMP, IPAddr}
import org.midonet.packets.NatState
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.collection.WeightedSelector

object Pool {
    def findPoolMember(ip: IPAddr, port: Int, pmArray: Array[PoolMember])
    : Boolean = {
        var i = 0
        while (i < pmArray.length) {
            val pm = pmArray(i)
            if (pm.address == ip && pm.protocolPort == port)
                return true
            i += 1
        }
        false
    }
}

final class Pool(val id: UUID, val adminStateUp: Boolean,
                 val lbMethod: PoolLBMethod,
                 val healthMonitorId: UUID,
                 val loadBalancerId: UUID,
                 val sessionPersistence: SessionPersistence,
                 val members: Array[PoolMember],
                 val activePoolMembers: Array[PoolMember],
                 val disabledPoolMembers: Array[PoolMember],
                 val vips: Array[Vip])
    extends VirtualDevice {

    override val deviceTag = FlowTagger.tagForPool(id)

    val isUp = adminStateUp && activePoolMembers.nonEmpty

    private val memberSelector = if (!isUp) null
                                 else WeightedSelector(activePoolMembers)

    /**
     * Choose an active pool member and apply DNAT to the packetContext
     * to redirect traffic to that pool member.
     *
     * If an existing NAT mapping is present, we respect that instead of mapping
     * to a new backend, in order to maintain existing connections.
     *
     * Return action based on outcome: ACCEPT if loadbalanced successfully,
     * DROP if no active pool member is available.
     */
    def loadBalance(context: PacketContext,
                    stickySourceIP: Boolean): Boolean = {
        implicit val implicitPacketContext = context

        context.addFlowTag(deviceTag)

        if (isUp) {
            val member = memberSelector.select()
            if (context.log.underlying.isDebugEnabled) {
                context.log.debug(s"Selected member $member out of {}",
                                  activePoolMembers.mkString(", "))
            }
            maintainConnectionOrLoadBalanceTo(member, stickySourceIP)
            true
        } else {
            maintainConnectionIfExists(stickySourceIP)
        }
    }

    /**
     * Applies the reverse load balance DNAT. If the source pool member is down
     * and it's a sticky IP, we drop the packet.
     */
    def reverseLoadBalanceValid(pktCtx: PacketContext, ip: IPAddr,
                                port: Int, stickySourceIP: Boolean): Boolean = {
        pktCtx.addFlowTag(deviceTag)
        isValidBackend(ip, port, stickySourceIP)
    }

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present, or to the specified pool member if not.
     *
     * Returns ACCEPT action when loadbalanced successfully.
     */
    private def maintainConnectionOrLoadBalanceTo(poolMember: PoolMember,
                                                  stickySourceIP: Boolean)
                                         (implicit context: PacketContext)
    : Unit =
        if (!applyExistingIfValidBackend(stickySourceIP))
            poolMember.applyDnat(context, stickySourceIP)

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present.
     *
     * Returns ACCEPT if loadbalanced successfully, DROP if no existing NAT
     * mapping was available.
     */
    def maintainConnectionIfExists(stickySourceIP: Boolean)
                                  (implicit context: PacketContext): Boolean = {
        // Even if there are no active pool members, we should keep
        // existing connections alive.
        val pktMatch = context.wcmatch
        if (stickySourceIP || pktMatch.getNetworkProto == ICMP.PROTOCOL_NUMBER) {
            // If all members are marked down, we stop connections
            context.log.debug("Stopping potential connection")
            false
        } else {
            applyExistingIfValidBackend(stickySourceIP = false)
        }
    }

    // Tries to apply a pre-existing connection if the backend is valid
    private def applyExistingIfValidBackend(stickySourceIP: Boolean)
                                            (implicit context: PacketContext)
    : Boolean = {
        val vipIp = context.wcmatch.getNetworkDstIP
        val vipPort = context.wcmatch.getDstPort
        val natKey = NatKey(context.wcmatch,
                            context.currentDevice,
                            if (stickySourceIP) NatState.FWD_STICKY_DNAT
                            else NatState.FWD_DNAT)
        if (context.applyIfExists(natKey)) {
            val backendIsValid = isValidBackend(context, stickySourceIP)
            context.log.debug(s"Found existing $natKey; backend valid: $backendIsValid")
            if (!backendIsValid) {
                // Reset the destination IP / port to be VIP IP / port
                context.wcmatch.setNetworkDst(vipIp)
                context.wcmatch.setDstPort(vipPort)

                // Delete the current NAT entry we found
                deleteNatEntry(context, stickySourceIP)
            }
            backendIsValid
        } else false
    }

    private def isValidBackend(context: PacketContext,
                               stickySourceIP: Boolean): Boolean =
        isValidBackend(context.wcmatch.getNetworkDstIP,
                       context.wcmatch.getDstPort,
                       stickySourceIP)

    /*
      * Decide if we should allow traffic to be loadbalanced to this ip:port
      *
      * If sticky source ip, we only send traffic to backends which are up
      *
      * If not sticky source IP, it's OK to send traffic to a disabled
      * backend until the mapping expires, to allow the connection to finish
      */
    private def isValidBackend(ip: IPAddr,
                               port: Int,
                               stickySourceIP: Boolean): Boolean =
        if (stickySourceIP)
            isActiveBackend(ip, port)
        else
            isActiveBackend(ip, port) || isDisabledBackend(ip, port)

    private def isActiveBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, activePoolMembers)

    private def isDisabledBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, disabledPoolMembers)

    val isStickySourceIP = sessionPersistence == SessionPersistence.SOURCE_IP

    private def deleteNatEntry(context: PacketContext,
                               stickySourceIP: Boolean): Unit = {
        val natKey = NatKey(context.wcmatch,
                            context.currentDevice,
                            if (stickySourceIP) NatState.FWD_STICKY_DNAT
                            else NatState.FWD_DNAT)
        context.deleteNatBinding(natKey)
    }

    override def toString =
        s"Pool [id=$id adminStateUp=$adminStateUp lbMethod=$lbMethod " +
        s"healtMonitorId=$healthMonitorId loadBalancerId=$loadBalancerId " +
        s"sessionPersistence=$sessionPersistence " +
        s"activePoolMembers=${activePoolMembers.toSeq} " +
        s"disabledPoolMembers=${disabledPoolMembers.toSeq} " +
        s"vips=${vips.toSeq}}]"

    override def equals(obj: Any) = obj match {
        case pool: Pool =>
            id == pool.id && adminStateUp == pool.adminStateUp &&
            lbMethod == pool.lbMethod &&
            healthMonitorId == pool.healthMonitorId &&
            loadBalancerId == pool.loadBalancerId &&
            sessionPersistence == pool.sessionPersistence &&
            util.Arrays.equals(members.asInstanceOf[Array[AnyRef]],
                               pool.members.asInstanceOf[Array[AnyRef]]) &&
            util.Arrays.equals(activePoolMembers.asInstanceOf[Array[AnyRef]],
                               pool.activePoolMembers.asInstanceOf[Array[AnyRef]]) &&
            util.Arrays.equals(disabledPoolMembers.asInstanceOf[Array[AnyRef]],
                               pool.disabledPoolMembers.asInstanceOf[Array[AnyRef]]) &&
            util.Arrays.equals(vips.asInstanceOf[Array[AnyRef]],
                               pool.vips.asInstanceOf[Array[AnyRef]])
        case _ => false
    }

    override def hashCode =
        Objects.hash(id, Boolean.box(adminStateUp), lbMethod, healthMonitorId,
                     loadBalancerId, sessionPersistence, members,
                     activePoolMembers, disabledPoolMembers, vips)
}
