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
package org.midonet.midolman.datapath

import java.util.{UUID, Set => JSet}

import scala.concurrent.{Future, ExecutionContext}

import com.typesafe.scalalogging.Logger

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.odp.DpPort
import org.midonet.odp.ports.InternalPort
import org.midonet.util.collection.Bimap
import org.midonet.util.concurrent.{SingleThreadExecutionContext, MultiLaneConveyorBelt}

object DatapathPortEntangler {
    trait Controller {
        def addToDatapath(interfaceName: String): Future[(DpPort, Int)]
        def removeFromDatapath(port: DpPort): Future[_]
        def setVportStatus(port: DpPort, vportId: UUID,
                           isActive: Boolean): Future[_]
    }
}

/**
 * This class manages the relationships between interfaces, datapath ports,
 * and virtual ports. In particular, it creates or removes datapath ports based
 * on the presence or absence of interfaces and virtual port bindings. An
 * external component can also register nternal datapath ports with this
 * class. This class DOES NOT manage tunnel ports.
 *
 * Before there is a port there must be a network interface. The
 * DatapathController does not create network interfaces (except in the
 * case of internal ports, where the network interface is created
 * automatically when the datapath port is created). Also, the
 * DatapathController does not change the status of network interfaces.
 *
 * The datapath's non-tunnel ports correspond to one of the following:
 * - port 0, the datapath's 'local' interface, whose name is the same as
 *   that of the datapath itself. It cannot be deleted, even if unused.
 * - ports corresponding to interface-to-virtual-port bindings; port 0 may
 *   be bound to a virtual port.
 *
 * The DatapathController must be the only software controlling its
 * datapath. Therefore, the datapath may not be deleted or manipulated in
 * any way by other components, inside or outside Midolman.
 *
 * However, the DatapathController is able to cope with other components
 * creating, deleting, or modifying the status of network interfaces.
 *
 * The DatapathController scans the host's network interfaces periodically
 * to track creations, deletions, and status changes:
 * - when a new network interface is created, if it corresponds to an
 *   interface-vport binding, then the DC adds it as a port on the datapath
 *   and records the correspondence of the resulting port's number to the
 *   virtual port. However, it does not consider the virtual port to be active
 *   unless the interface's status is UP, in which case it also sends a
 *   LocalPortActive(vportID, active=true) message to the
 *   VirtualToPhysicalMapper.
 * - when a network interface is deleted, if it corresponds to a datapath
 *   port, then the datapath port is removed and the port number reclaimed,
 *   unless it's an internal port.
 *   If the interface was bound to a virtual port, then the DC also sends a
 *   LocalPortActive(vportID, active=false) message to the
 *   VirtualToPhysicalMapper.
 * - when a network interface status changes from UP to DOWN, if it was bound
 *   to a virtual port, the DC sends a LocalPortActive(vportID, active=false)
 *   message to the VirtualToPhysicalMapper.
 * - when a network interface status changes from DOWN to UP, if it was bound
 *   to a virtual port, the DC sends a LocalPortActive(vportID, active=true)
 *   message to the VirtualToPhysicalMapper.
 *
 * The DatapathController receives updates to the host's interface-vport
 * bindings:
 * - when a new binding is discovered, if the interface already exists then
 *   the DC adds it as a port on the datapath and records the correspondence
 *   of the resulting port number to the virtual port. However, it does not
 *   consider the virtual port to be active unless the interface's
 *   status is UP, in which case it also sends a LocalPortActive(vportID,
 *   active=true) message to the VirtualToPhysicalMapper.
 * - when a binding is removed, if a corresponding port already exists on
 *   the datapath, then the datapath port is removed and the port number
 *   reclaimed, unless it's an internal port. If the virtual port was bound to
 *   an interface, then the DC also sends a LocalPortActive(vportID, active=false)
 *   message to the VirtualToPhysicalMapper.
 */
trait DatapathPortEntangler {
    val controller: DatapathPortEntangler.Controller
    implicit val ec: SingleThreadExecutionContext
    implicit val log: Logger

    var interfaceToStatus = Map[String, Boolean]()
    var interfaceToDpPort = Map[String, DpPort]()
    var dpPortNumToInterface = Map[Integer, String]()
    var interfaceToVport = new Bimap[String, UUID]()

    // Sequentializes updates to a particular port. Note that while an update
    // is in progress, new updates can be scheduled.
    private val conveyor = new MultiLaneConveyorBelt[String](_ => {
        /* errors are logged elsewhere */
    })

    /**
     * Registers an internal port (namely, port 0)
     */
    def registerInternalPort(port: InternalPort): Unit =
        conveyor handle (port.getName, (name, _) => {
            interfaceToDpPort += name -> port
            dpPortNumToInterface += port.getPortNo -> name
            Future successful null
        })

    /**
     * Register new interfaces, update their status or delete them.
     */
    def updateInterfaces(itfs: JSet[InterfaceDescription]): Unit = {
        var interfacesToDelete = interfaceToStatus.keySet

        val it = itfs.iterator()
        while (it.hasNext) {
            val itf = it.next()
            interfacesToDelete -= itf.getName
            conveyor.handle(itf.getName, (port, _) => processUpdate(itf, port))
        }

        interfacesToDelete foreach (conveyor.handle(_, deleteInterface))
    }

    /**
     * We do not support remapping a vport to a different interface or vice-versa.
     * We assume each vport ID and interface will occur in at most one binding.
     */
    def updateVPortInterfaceBindings(vportToInterface: Map[UUID, String]): Unit = {
        log.debug(s"updating vport to interface bindings: $vportToInterface")

        for ((vport, ifname) <- vportToInterface if !interfaceToVport.contains(ifname)) {
            conveyor handle (ifname, (port, _) => newInterfaceVportBinding(vport, port))
        }

        for ((ifname, vport) <- interfaceToVport if !vportToInterface.contains(vport)) {
            conveyor handle (ifname, deletedInterfaceVportBinding(vport, _, _))
        }
    }

    private def processUpdate(itf: InterfaceDescription, port: String): Future[_] =
        if (interfaceToStatus contains port) {
            updateInterface(itf, port)
        } else {
            newInterface(itf, port)
        }

    private def newInterface(itf: InterfaceDescription, port: String): Future[_] = {
        val isUp = itf.isUp
        log.info(s"Found new interface $itf which is ${if (isUp) "up" else "down"}")
        interfaceToStatus += port -> isUp
        tryCreateDpPort(port)
    }

    private def newInterfaceVportBinding(vport: UUID, ifname: String): Future[_] = {
        log.debug(s"Creating binding $ifname -> $vport")
        interfaceToVport += ifname -> vport
        tryCreateDpPort(ifname)
    }

    private def tryCreateDpPort(port: String): Future[_] = {
        val vPort = interfaceToVport get port
        val itf = interfaceToStatus get port
        if (vPort.isDefined && itf.isDefined) {
            val dpPort = interfaceToDpPort get port
            if (dpPort.isDefined) { // If it was registered
                dpPortAdded(dpPort.get)
            } else {
                addDpPort(port)
            }
        } else {
            Future successful null
        }
    }

    /**
     * Updates the status of the interface. Updates the state of the port is a
     * datapath port exists or else it tries to create one. A particular case
     * is as follows: the NetlinkInterfaceSensor sets the endpoint for all the
     * ports of the dp to DATAPATH. If the endpoint is not DATAPATH it means
     * that this is a dangling tap. We need to recreate the dp port. Use case:
     * add tap, bind it to a vport, remove the tap. The dp port gets destroyed.
     */
    private def updateInterface(itf: InterfaceDescription, port: String): Future[_] = {
        val name = itf.getName
        val isUp = itf.isUp
        val wasUp = interfaceToStatus(port)
        interfaceToStatus += itf.getName -> isUp

        val dpPort = interfaceToDpPort get name
        val vPort = interfaceToVport get name
        if (dpPort.isDefined && vPort.isDefined) {
            if (isDangling(itf, isUp)) {
                updateDangling(dpPort.get, name)
            } else if (isUp != wasUp) {
                changeStatus(dpPort.get, itf, isUp)
            } else {
                Future successful null
            }
        } else {
            tryCreateDpPort(name) // In case we failed to create it before
        }
    }

    private def isDangling(itf: InterfaceDescription, isUp: Boolean): Boolean =
        itf.getEndpoint != InterfaceDescription.Endpoint.UNKNOWN &&
        itf.getEndpoint != InterfaceDescription.Endpoint.DATAPATH &&
        isUp

    private def updateDangling(dpPort: DpPort, name: String): Future[_] = {
        log.debug(s"Recreating port $name because it was removed and the dp" +
                   "didn't request the removal")
        interfaceToDpPort -= name
        dpPortNumToInterface -= dpPort.getPortNo
        tryCreateDpPort(name)
    }

    private def changeStatus(dpPort: DpPort, itf: InterfaceDescription,
                             isUp: Boolean): Future[_] = {
        log.info(s"Interface $itf is now ${if (isUp) "up" else "down"}")
        val vport = interfaceToVport.get(itf.getName)
        if (vport.isDefined) { // This can be a registered port with no binding
            controller.setVportStatus(dpPort, vport.get, isUp)
        } else {
            Future successful null
        }
    }

    private def deleteInterface(port: String, scheduleShutdown: () => Unit): Future[_] = {
        log.info("Deleting interface {}", port)
        tryRemovePort(port, scheduleShutdown) {
            interfaceToStatus -= port
        }
    }

    private def deletedInterfaceVportBinding(vport: UUID, name: String,
                                             scheduleShutdown: () => Unit): Future[_] = {
        log.info(s"Deleting binding of port $vport to $name")
        tryRemovePort(name, scheduleShutdown) {
            interfaceToVport -= name
        }
    }

    private def tryRemovePort(port: String, scheduleShutdown: () => Unit)
                             (removeFromMap: => Unit): Future[_] = {
        val dpPort = interfaceToDpPort get port
        val res =
            if (dpPort.isDefined) {
                Future.sequence(List(removeIfNeeded(dpPort.get, port),
                                     deactivateIfNeeded(dpPort.get, port)))
            } else {
                Future successful null
            }

        removeFromMap

        if (!(interfaceToStatus contains port) &&
            !(interfaceToVport contains port)) {
            scheduleShutdown()
        }

        res
    }

    private def addDpPort(port: String): Future[_] =
        (controller addToDatapath port) flatMap { case (dpPort, _) =>
            log.debug(s"Datapath port $port added")
            interfaceToDpPort += port -> dpPort
            dpPortNumToInterface += dpPort.getPortNo -> port
            dpPortAdded(dpPort)
        } recover { case t =>
            // We'll retry on the next interface scan
            log.warn(s"Failed to create port $port: ${t.getMessage}")
        }

    private def dpPortAdded(port: DpPort): Future[_] = {
        val name = port.getName
        if (interfaceToStatus(name)) {
            val vport = interfaceToVport.get(name).get
            controller.setVportStatus(port, vport, isActive = true)
        } else {
            Future successful null
        }
    }

    private def removeIfNeeded(dpPort: DpPort, name: String): Future[_] =
        if (!dpPort.isInstanceOf[InternalPort]) {
            interfaceToDpPort -= name
            dpPortNumToInterface -= dpPort.getPortNo
            (controller removeFromDatapath dpPort) recover { case t =>
                // We got ourselves a dangling port
                log.warn(s"Failed to remove port $dpPort: ${t.getMessage}")
            }
        } else {
            Future successful null
        }

    private def deactivateIfNeeded(dpPort: DpPort, name: String): Future[_] = {
        val vport = interfaceToVport get name
        val status = interfaceToStatus get name

        if (vport.isDefined && status.isDefined && status.get) {
            controller.setVportStatus(dpPort, vport.get, isActive = false)
        } else {
            Future successful null
        }
    }
}
