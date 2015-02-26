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

package org.midonet.cluster.services.vxgw

import java.util.UUID

import scala.util.Try

import rx.{Observable, Observer}

import org.midonet.cluster.southbound.vtep.VtepDataClientFactory
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.vtep.model.{MacLocation, LogicalSwitch}
import org.midonet.midolman.state.ZookeeperConnectionWatcher
import org.midonet.packets.IPv4Addr

/** Defines the interface that any VTEP would offer to a VxGW manager in order
  * to participate in a VxGW among several other VTEPs (either hardware or a
  * Neutron Network.  The VTEP with a hardware switch typically through an OVSDB
  * connection which details are abstracted by implementations. */
trait Vtep {

    /** Make this VTEP peer participate in the given Logical Switch */
    def join(vxgw: VxlanGateway, primeWith: Iterable[MacLocation])

    /** Make this VTEP abandon the given Logical Switch */
    def abandon(vxgw: VxlanGateway)

    /** LogicalSwitches in which this VTEP participates */
    def memberships: Seq[VxlanGateway]
}


/** The interface used to interact with an OVSDB server located at a hardware
  * VTEP */
abstract class VtepConfig(val mgmtIp: IPv4Addr, val mgmtPort: Int) {

    /** The Observable that emits updates in the *cast_Mac_Local tables, with
      * MACs that are local to the VTEP and should be published to other
      * members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** The Observer to use in order to push updates about MACs that are local
      * to other VTEPs (which includes ports in MidoNet.  Entries pushed to this
      * Observer are expected to be applied in the Mac_Remote tables on the
      * hardware VTEPs. */
    def macRemoteUpdater: Observer[MacLocation]

    /** Provide a snapshot with the current contents of the Mac_Local tables
      * in the VTEP's OVSDB. If a logical switch UUID is given, it'll filter
      * only entries in it. */
    def currentMacLocal(ls: UUID): Seq[MacLocation]

    /** This method should return the tunnel IP of the VTEP */
    def vxlanTunnelIp: Option[IPv4Addr]

    /** Ensure that the hardware VTEP's config contains a Logical Switch with
      * the given name and VNI. */
    def ensureLogicalSwitch(name: String, vni: Int): Try[LogicalSwitch]

    /** Ensure that the hardware VTEP's config for the given Logical Switch
      * contains these and only these bindings. */
    def ensureBindings(lsName: String, bindings: Iterable[(String, Short)]): Try[Unit]

    /** Remove the logical switch with the given name, as well as all bindings
      * and entries in Mac tables. */
    def removeLogicalSwitch(name: String): Try[Unit]
}

class VtepConfigException(msg: String) extends RuntimeException(msg)

/** Offers a pool of interfaces to hardware VTEPs. */
class VtepPool(nodeId: UUID, topology: TopologyApi,
               zkConnWatcher: ZookeeperConnectionWatcher,
               tzState: TunnelZoneStatePublisher,
               vtepDataClientFactory: VtepDataClientFactory) {

    import java.util.concurrent.ConcurrentHashMap
    import com.google.common.annotations.VisibleForTesting

    private val pool = new ConcurrentHashMap[IPv4Addr, Vtep]()

    /** Acquire the interface to a hardware VTEP with its management interface
      * located at the given ip and port, reusing the existing instance or
      * creating a new one. */
    def fish(mgmtIp: IPv4Addr, mgmtPort: Int): Vtep = {
        pool.get(mgmtIp) match {
            case null =>
                val newPeer = create(mgmtIp, mgmtPort)
                val previous = pool.putIfAbsent(mgmtIp, newPeer)
                if (previous == null) { // TODO: cleanup the created peer?
                    newPeer
                } else {
                    previous
                }
            case vtep => vtep
        }
    }

    /** Acquire the interface to a hardware VTEP if it exists in the pool,
      * but don't create it otherwise. */
    def fishIfExists(mgmtIp: IPv4Addr, mgmtPort: Int): Option[Vtep] = {
        Option(pool.get(mgmtIp))
    }

    @VisibleForTesting
    def create(mgmtIp: IPv4Addr, mgmtPort: Int): Vtep = {
        val vtepFromOvsdb = new VtepFromOldOvsdbClient(nodeId, mgmtIp, mgmtPort,
                                                       vtepDataClientFactory)

        new VtepController(vtepFromOvsdb, topology, zkConnWatcher, tzState)
    }

}





