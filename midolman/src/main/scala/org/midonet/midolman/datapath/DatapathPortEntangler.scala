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
package org.midonet.midolman.datapath

import java.util.{Set => JSet, HashSet, UUID}

import scala.concurrent.Future

import com.typesafe.scalalogging.Logger
import org.midonet.midolman.DatapathStateDriver
import org.midonet.midolman.DatapathStateDriver.DpTriad
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.rcu.PortBinding
import org.midonet.odp.DpPort
import org.midonet.odp.ports.InternalPort
import org.midonet.util.concurrent._

/**
 * This class manages the relationships between interfaces, datapath ports,
 * and virtual ports. In particular, it creates or removes datapath ports based
 * on the presence or absence of interfaces and virtual port bindings. An
 * external component can also register internal datapath ports with this
 * class. This class DOES NOT manage tunnel ports.
 *
 * Before there is a port there must be a network interface. The
 * DatapathController (DpC) does not create network interfaces (except in the
 * case of internal ports, where the network interface is created
 * automatically when the datapath port is created). Also, the DpC does not
 * change the status of network interfaces.
 *
 * The datapath's non-tunnel ports correspond to one of the following:
 * - port 0, the datapath's 'local' interface, whose name is the same as
 *   that of the datapath itself. It cannot be deleted, even if unused.
 * - ports corresponding to interface-to-virtual-port bindings; port 0 may
 *   be bound to a virtual port.
 *
 * The DpC must be the only software controlling its datapath. Therefore, the
 * datapath may not be deleted or manipulated in any way by other components,
 * inside or outside Midolman.
 *
 * However, the DpC is able to cope with other components creating, deleting,
 * or modifying the status of network interfaces.
 *
 * The DpC scans the host's network interfaces periodically to track creations,
 * deletions, and status changes:
 * - when a network interface is created or goes up, if it corresponds to an
 *   interface-vport binding, then the DpC adds it as a port on the datapath
 *   and records the correspondence of the resulting port's number to the
 *   virtual port. It also broadcasts a LocalPortActive(vportID, active=true)
 *   message.
 * - when a network interface is deleted or goes down, if it corresponds to a
 *   datapath port, then the datapath port is removed and the port number
 *   reclaimed, unless it's an internal port.
 *   If the interface was bound to a virtual port, then the DC broadcasts a
 *   LocalPortActive(vportID, active=false) message
 *
 * The DpC receives updates to the host's interface-vport bindings:
 * - when a new binding is discovered, if the interface already exists and is
 *   up, then the DpC adds it as a port on the datapath and records the
 *   correspondence of the resulting port's number to the virtual port. It also
 *   broadcasts a LocalPortActive(vportID, active=true) message.
 * - when a binding is removed, if a corresponding port already exists on
 *   the datapath, then the datapath port is removed and the port number
 *   reclaimed, unless it's an internal port. If the virtual port was bound to
 *   an interface, then the DC also sends a LocalPortActive(vportID, active=false)
 *   message to the VirtualToPhysicalMapper.
 */
trait DatapathPortEntangler {
    protected val driver: DatapathStateDriver
    protected implicit val singleThreadExecutionContext: SingleThreadExecutionContext
    protected implicit val log: Logger

    // Sequentializes updates to a particular port. Note that while an update
    // is in progress, new updates can be scheduled.
    private val conveyor = new MultiLaneConveyorBelt[String](_ => {
        /* errors are logged elsewhere */
    })

    def addToDatapath(interfaceName: String): Future[(DpPort, Int)]
    def removeFromDatapath(port: DpPort): Future[_]
    def setVportStatus(
        port: DpPort, vport: UUID, tunnelKey: Long, isActive: Boolean): Unit

    def interfaceToTriad = driver.interfaceToTriad
    def vportToTriad = driver.vportToTriad
    def keyToTriad = driver.keyToTriad
    def dpPortNumToTriad = driver.dpPortNumToTriad

    /**
     * Registers an internal port (namely, port 0)
     */
    def registerInternalPort(port: InternalPort): Unit =
        conveyor handle (port.getName, () => {
            val triad = getOrCreate(port.getName)
            triad.dpPort = port
            interfaceToTriad.put(port.getName, triad)
            Future successful null
        })

    /**
     * Recreate OVS datapath port. This SHOULD be called only when the datapath
     * port is deleted from the datapath outside the management of Midolman.
     *
     * @param port the OVS datapath port to be recreated.
     */
    def recreateDpPortIfNeeded(port: DpPort): Unit =
        conveyor handle (port.getName, () => {
            val portNo = port.getPortNo
            if (dpPortNumToTriad.contains(portNo)) {
                log.debug(s"Recreating port #$portNo because it was removed " +
                    "and the DPC didn't request the removal")
                val triad@DpTriad(_, isUp, _, _, _, _) =
                    dpPortNumToTriad.get(portNo)
                if (isUp) {
                    dpPortNumToTriad.remove(portNo)
                    addDpPort(triad)
                } else {
                    log.debug(s"The port #$portNo is not up, so did nothing")
                    Future.successful(null)
                }
            } else {
                Future.successful(null)
            }
        })

    /**
     * Register new interfaces, update their status or delete them.
     */
    def updateInterfaces(itfs: JSet[InterfaceDescription]): Unit = {
        val interfacesToDelete = new HashSet(interfaceToTriad.keySet())
        val it = itfs.iterator()
        while (it.hasNext) {
            val itf = it.next()
            interfacesToDelete.remove(itf.getName)
            conveyor.handle(itf.getName, () => processInterface(itf, itf.getName))
        }

        val toDelete = interfacesToDelete.iterator()
        while (toDelete.hasNext) {
            val ifname = toDelete.next()
            if (!itfs.contains(ifname)) {
                conveyor.handle(ifname, () => deleteInterface(
                    interfaceToTriad.get(ifname)))
            }
        }
    }

    /**
     * We do not support remapping a vport to a different interface or vice-versa.
     * We assume each vport ID and interface will occur in at most one binding.
     */
    def updateVPortInterfaceBindings(bindings: Map[UUID, PortBinding]): Unit = {
        log.debug(s"Updating vport to interface bindings: $bindings")

        for ((vportId, PortBinding(_, tunnelKey, ifname)) <- bindings) {
            if (!vportToTriad.containsKey(vportId)) {
                conveyor handle (ifname, () =>
                    newInterfaceVportBinding(vportId, bindings(vportId).tunnelKey, ifname))
            }
        }

        val it = vportToTriad.entrySet().iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!bindings.contains(entry.getKey)) {
                val triad = entry.getValue
                conveyor handle (triad.ifname, () =>
                    deletedInterfaceVportBinding(triad))
            }
        }
    }

    /**
     * Updates the status of the interface. Updates the state of the port if a
     * datapath port exists or else it tries to create one. A particular case
     * is as follows: the NetlinkInterfaceSensor sets the endpoint for all the
     * ports of the dp to DATAPATH. If the endpoint is not DATAPATH it means
     * that this is a dangling tap. We need to recreate the dp port. Use case:
     * add tap, bind it to a vport, remove the tap. The dp port gets destroyed.
     */
    private def processInterface(itf: InterfaceDescription, ifname: String): Future[_] = {
        val isUp = itf.isUp
        val triad = getOrCreate(ifname)
        val wasUp = triad.isUp

        if (isUp && !wasUp) {
            triad.isUp = isUp
            tryCreateDpPort(triad)
        } else if (!isUp) {
            deleteInterface(triad)
        } else {
            Future successful null
        }
    }

    private def newInterfaceVportBinding(vport: UUID, tunnelKey: Long, ifname: String): Future[_] = {
        val triad = getOrCreate(ifname)
        triad.vport = vport
        triad.tunnelKey = tunnelKey
        vportToTriad.put(vport, triad)
        tryCreateDpPort(triad)
    }

    private def getOrCreate(ifname: String): DpTriad = {
        var status = interfaceToTriad.get(ifname)
        if (status eq null) {
            status = DpTriad(ifname)
            interfaceToTriad.put(ifname, status)
        }
        status
    }

    private def deleteInterface(triad: DpTriad): Future[_] =
        tryRemoveDpPort(triad) map { x =>
            triad.isUp = false
            shutdownIfNeeded(triad)
        }

    private def deletedInterfaceVportBinding(triad: DpTriad): Future[_] =
        tryRemoveDpPort(triad) map { _ =>
            vportToTriad.remove(triad.vport)
            triad.vport = null
            shutdownIfNeeded(triad)
        }

    private def tryCreateDpPort(triad: DpTriad): Future[_] = {
        if ((triad.vport ne null) && triad.isUp) {
            log.info(s"Binding port ${triad.vport} to ${triad.ifname}")
            val dpPort = triad.dpPort
            if (dpPort ne null) { // If it was registered
                setVportStatus(triad, active = true)
                triad.dpPortNo = dpPort.getPortNo
                dpPortNumToTriad.put(dpPort.getPortNo, triad)
                Future successful null
            } else {
                addDpPort(triad)
            }
        } else {
            Future successful null
        }
    }

    private def tryRemoveDpPort(triad: DpTriad): Future[_] =
        if ((triad.vport ne null) && triad.isUp) {
            log.info(s"Unbinding port ${triad.vport} from ${triad.ifname}")
            setVportStatus(triad, active = false)
            dpPortNumToTriad.remove(triad.dpPortNo)
            triad.dpPortNo = null
            if (!isInternal(triad)) {
                val dpPort = triad.dpPort
                triad.dpPort = null
                removeFromDatapath(dpPort) recover { case t =>
                    // We got ourselves a dangling port
                    log.warn(s"Failed to remove port $dpPort: ${t.getMessage}")
                }
            } else Future successful null
        } else {
            Future successful null
        }

    private def shutdownIfNeeded(triad: DpTriad): Unit =
        if (!triad.isUp && (triad.vport eq null) && !isInternal(triad)) {
            interfaceToTriad.remove(triad.ifname)
            conveyor.shutdown(triad.ifname)
        }

    private def isInternal(triad: DpTriad): Boolean =
        (triad.dpPort ne null) && triad.dpPort.isInstanceOf[InternalPort]

    private def addDpPort(triad: DpTriad): Future[_] =
        addToDatapath(triad.ifname) map { case (dpPort, _) =>
            log.info(s"Datapath port ${triad.ifname} added")
            triad.dpPort = dpPort
            triad.dpPortNo = dpPort.getPortNo
            dpPortNumToTriad.put(dpPort.getPortNo, triad)
            setVportStatus(triad, active = true)
        } recover { case t =>
            // We'll retry on the next interface scan
            triad.isUp = false
            log.warn(s"Failed to create port ${triad.ifname}: ${t.getMessage}")
        }

    private def setVportStatus(triad: DpTriad, active: Boolean): Unit = {
        if (active)
            keyToTriad.put(triad.tunnelKey, triad)
        else
            keyToTriad.remove(triad.tunnelKey)
        setVportStatus(triad.dpPort, triad.vport, triad.tunnelKey, active)
    }
}
