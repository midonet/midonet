/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.vtep

import org.midonet.packets.IPv4Addr
import org.midonet.vtep.model.LogicalSwitch
import rx.{Observer, Observable}

import scala.util.Try

/**
 * A trait representing the data operations that can be performed on a VTEP
 */
trait VtepDataClient {
    /**
     * @return the VTEP tunnel IP
     */
    def vxlanTunnelIp: Option[IPv4Addr]

    /** The Observable that emits updates in the *cast_Mac_Local tables, with
      * MACs that are local to the VTEP and should be published to other
      * members of a VxLAN gateway. */
    def macLocalUpdates: Observable[MacLocation]

    /** The Observer to use in order to push updates about MACs that are local
      * to other VTEPs (which includes ports in MidoNet.  Entries pushed to this
      * Observer are expected to be applied in the Mac_Remote tables on the
      * hardware VTEPs. */
    def macRemoteUpdates: Observer[MacLocation]

    /** Provide a snapshot with the current contents of the Mac_Local tables
      * in the VTEP's OVSDB. */
    def currentMacLocal: Seq[MacLocation]

    /** Ensure that the hardware VTEP's config contains a Logical Switch with
      * the given name and VNI. */
    def ensureLogicalSwitch(name: String, vni: Int): Try[LogicalSwitch]

    /** Remove the logical switch with the given name, as well as all bindings
      * and entries in Mac tables. */
    def removeLogicalSwitch(name: String): Try[Unit]

    /** Ensure that the hardware VTEP's config for the given Logical Switch
      * contains these and only these bindings. */
    def ensureBindings(lsName: String, bindings: Iterable[(String, Short)]): Try[Unit]
}





