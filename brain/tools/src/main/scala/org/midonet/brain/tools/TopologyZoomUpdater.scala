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

package org.midonet.brain.tools

import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}

import org.midonet.brain.{TopologyZoomUpdaterConfig, BrainConfig}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import com.google.protobuf.Message
import org.slf4j.LoggerFactory

import org.midonet.brain.tools.TopologyEntity._
import org.midonet.brain.tools.TopologyZoomUpdater._
import org.midonet.cluster.data.storage.StorageWithOwnership
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.models.Topology.Host.PortBinding
import org.midonet.cluster.models.Topology.IpAddrGroup.IpAddrPorts
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.packets.{IPAddr, IPv4Addr}
import org.midonet.util.concurrent.toFutureOps
import org.midonet.util.functors.makeRunnable

/**
 * Topology Zoom Updater service for testing topology components.
 * It creates a set of topology elements and interconnects them.
 * Please note that this is just for testing purposes and the data
 * in the objects and the connections between them may not be
 * consistent with an actual network architecture.
 *
 * This updater generates two categories of topology objects:
 *  - 'fixed' topology objects are created during initialization and
 *    never removed (though they can be modified). This 'fixed' objects
 *    act as anchors for addition and removal of other topology objects.
 *  - the rest of objects are 'temporary' and they can be removed during
 *    updates (new temporary objects can be created either during
 *    initialization or during the updates).
 *
 * The topology created is as follows:
 * - Fixed VTEP
 * - Temporary VTEPS (can be added and removed dynamically)
 * - Fixed TunnelZone
 *    - Fixed Host
 *    - Fixed IpAddrGroup
 *       - Temporary Hosts (can be added and removed dynamically)
 * - Provider Router
 *    - Fixed Router
 *       - Fixed Network
 *          * Contains tmpPorts bound to the tmpVteps (fixed and temporary)
 *          * Contains tmpPorts bound to tmpHosts (fixed and temporary)
 *          - fixed PortGroup
 *             * Contains temporary tmpPorts
 *    - Temporary Routers (can be added and removed dynamically)
 *       - Temporary Networks
 * - Fixed Rule
 * - Fixed Chain
 * - Fixed Route
 */
object TopologyZoomUpdater {
    val random = new Random()
    val owner = UUID.randomUUID()

    def randomIp: String = IPv4Addr.random.toString
    def randomId: Commons.UUID = UUIDUtil.randomUuidProto
}
class TopologyZoomUpdater @Inject()(val backend: MidonetBackend,
                                    val brainConf: BrainConfig)
    extends AbstractService {
    private val cfg: TopologyZoomUpdaterConfig = brainConf.topologyUpdater

    implicit val storage = backend.ownershipStore
    private val log = LoggerFactory.getLogger(classOf[TopologyZoomUpdater])
    private val pool = Executors.newScheduledThreadPool(cfg.threads)

    object Operation extends Enumeration {
        type Operation = Value
        val ADDITION, REMOVAL, UPDATE = Value
    }
    import Operation._

    private val runnable: Runnable =
        makeRunnable({try {performUpdate()} catch {
            case NonFatal(e) => log.error("failed scheduled execution", e)
        }})

    // NOTE: We need to make sure that the objects we handle are in sync with
    // zk, so we keep the identifiers and reload the objects from zk before
    // using them
    // Suggestions for alternatives are welcome :-)

    private var providerRouterId: Commons.UUID = _
    def providerRouter: Router = Router.get(providerRouterId).get

    private var fixedTunnelZoneId: Commons.UUID = _
    def fixedTunnelZone: TunnelZone = TunnelZone.get(fixedTunnelZoneId).get

    private var fixedRouterId: Commons.UUID = _
    def fixedRouter: Router = Router.get(fixedRouterId).get

    private var fixedNetworkId: Commons.UUID = _
    def fixedNetwork: Network = Network.get(fixedNetworkId).get

    private var fixedHostId: Commons.UUID = _
    def fixedHost: Host = Host.get(fixedHostId).get

    private var fixedVtepId: Commons.UUID = _
    def fixedVtep: Vtep = Vtep.get(fixedVtepId).get

    private var fixedPortGroupId: Commons.UUID = _
    def fixedPortGroup: PortGroup = PortGroup.get(fixedPortGroupId).get

    private var fixedIpAddrGroupId: Commons.UUID = _
    def fixedIpAddrGroup: IpAddrGroup = IpAddrGroup.get(fixedIpAddrGroupId).get

    private var fixedChainId: Commons.UUID = _
    def fixedChain: Chain = Chain.get(fixedChainId).get

    private var fixedRuleId: Commons.UUID = _
    def fixedRule: Rule = Rule.get(fixedRuleId).get

    private var fixedRouteId: Commons.UUID = _
    def fixedRoute: Route = Route.get(fixedRouteId).get

    /** updatable Routers */
    def tmpRouters: Iterable[Router] =
        providerRouter.getRemoteDevices collect
            {case r: Router if r.getId != fixedRouterId => r}

    /** updatable Networks */
    def tmpNetworks: Iterable[Network] =
        tmpRouters flatMap {_.getRemoteDevices} collect {case n: Network => n}

    /** updatable Ports */
    def tmpPorts: Iterable[Port] =
        fixedNetwork.getPorts filter {_.getTargetDevice == None}

    /** updatable Vteps */
    private val tmpVtepIds: mutable.Set[Commons.UUID] = mutable.Set()
    def tmpVteps: Iterable[Vtep] = tmpVtepIds flatMap {Vtep.get(_)}

    /** updatable Hosts */
    private val tmpHostIds: mutable.Set[Commons.UUID] = mutable.Set()
    def tmpHosts: Iterable[Host] = tmpHostIds flatMap {Host.get(_)}

    // A monotonically increasing counter to generate distinct names for
    // newly created objects.
    private var count: Long = 0

    @Override
    override def doStart(): Unit = {
        log.info("Starting the Topology Zoom Updater")

        try {
            if (cfg.enableUpdates && cfg.period <= 0)
                throw new IllegalArgumentException(
                    "invalid update interval (periodMs): " + cfg.period)

            buildLayout()
            if (cfg.enableUpdates)
                pool.scheduleAtFixedRate(runnable, cfg.period, cfg.period,
                                         TimeUnit.MILLISECONDS)

            log.info("Updater started")
            notifyStarted()
        } catch {
            case e: Exception =>
                log.warn("Updater failed to start")
                notifyFailed(e)
        }
    }

    @Override
    override def doStop(): Unit = {
        log.info("Stopping the Updater")
        pool.shutdown()
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.error("Unable to shutdown Updater thread pool")
                }
            }
        } catch {
            case e: InterruptedException =>
                log.warn("Interrupted while waiting for completion")
                pool.shutdownNow()
                Thread.currentThread().interrupt() // preserve status
        } finally {
            cleanUp()
        }
        log.info("Updater stopped")
        notifyStopped()
    }

    private def buildLayout() = {
        log.debug("building initial layout")

        providerRouterId = Router("providerRouter").create().getId
        fixedTunnelZoneId = TunnelZone("fixedTunnelZone",
                                       Topology.TunnelZone.Type.GRE).create().getId
        fixedRouterId = Router("fixedRouter").create().linkTo(providerRouter).getId
        fixedNetworkId = Network("fixedNetwork").create().linkTo(fixedRouter).getId
        fixedHostId = Host("fixedHost").create().getId
        fixedTunnelZone.addHost(fixedHost)
        fixedVtepId = Vtep("fixedVtep").create().getId
        fixedVtep.tunnelZone.addHost(fixedHost)
        fixedPortGroupId = PortGroup("fixedPortGroup").create().getId
        fixedIpAddrGroupId = IpAddrGroup("fixedIpAddrGroup").create().getId
        fixedChainId = Chain("fixedChain").create().getId
        fixedRuleId = Rule().create().getId
        fixedRouteId = Route().create().getId

        for (idx <- 0 to cfg.initialRouters - 1)
            addRouter()
        for (idx <- 0 to cfg.initialVteps - 1)
            addVtep()
        for (idx <- 0 to cfg.initialHosts - 1)
            addHost()
        for (idx <- 0 to cfg.initialPortsPerNetwork - 1)
            fixedPortGroup.addPort(fixedNetwork.createPort())
    }

    private def cleanUp() = {
        fixedRoute.delete()
        fixedRule.delete()
        fixedChain.delete()
        tmpVteps foreach rmVtep
        tmpHosts foreach rmHost
        fixedVtep.delete()
        fixedHost.delete()
        fixedPortGroup.delete()
        fixedIpAddrGroup.delete()
        fixedTunnelZone.delete()
        providerRouter.delete()
    }

    private def addRouter() = {
        count += 1
        val rt = Router("r" + count).create().linkTo(providerRouter)
        for (p <- 0 to cfg.initialNetworksPerRouter - 1) {
            addNetwork(rt)
        }
    }

    private def addNetwork(rt: Router) = {
        count += 1
        Network("b_" + rt.getName + "_" + count).create().linkTo(rt)
    }

    private def addVtep() = {
        count += 1
        val vtep = Vtep("vtep" + count).create()
        tmpVtepIds.add(vtep.getId)
        fixedNetwork.bindVtep(vtep, fixedHost)
        vtep.tunnelZone.addHost(fixedHost)
    }

    private def addHost() = {
        count += 1
        val host = Host("host" + count).create()
        tmpHostIds.add(host.getId)
        fixedTunnelZone.addHost(host)
        fixedIpAddrGroup.addAddress(host.address)
    }

    private def updateNetwork(br: Network) =
        br.setAdminStateUp(!br.getAdminStateUp)

    private def updateRouter(rt: Router) =
        rt.setAdminStateUp(!rt.getAdminStateUp)

    private def updatePort(p: Port) =
        p.setAdminStateUp(!p.getAdminStateUp)

    private def updateVtep(vt: Vtep) = {
        // do nothing
    }

    private def updateHost(h: Host) = {
        // do nothing
    }

    private def rmVtep(vt: Vtep) = {
        tmpVtepIds.remove(vt.getId)
        vt.delete()
    }

    private def rmHost(h: Host) = {
        tmpHostIds.remove(h.getId)
        fixedIpAddrGroup.removeAddress(h.address)
        h.delete()
    }

    /* choose the next operation (removal, addition or update)
     * @param cur: is the current number of elements
     * @param initial: is the initial number of elements
     */
    private def chooseOperation(cur: Int, initial: Int): Operation = {
        random.nextInt(3) match {
            case 0 => UPDATE
            case 1 => if (cur <= (initial / 2)) UPDATE else REMOVAL
            case 2 => if (cur >= (initial + initial / 2)) UPDATE else ADDITION
        }
    }

    private def getRandomEntry[T: ClassTag](list: Iterable[T]): T =
        list.toArray.apply(random.nextInt(list.size))

    /* perform a random operation */
    private def performUpdate() = {
        random.nextInt(5) match {
            case 0 =>
                log.info("updating tmpRouters")
                val rt = getRandomEntry(tmpRouters)
                chooseOperation(tmpRouters.size, cfg.initialRouters) match {
                    case UPDATE => updateRouter(rt)
                    case REMOVAL => rt.delete()
                    case ADDITION => addRouter()
                }
            case 1 =>
                log.info("updating tmpNetworks")
                val rt = getRandomEntry(tmpRouters)
                val br = getRandomEntry(tmpNetworks)
                chooseOperation(tmpNetworks.size,
                                cfg.initialRouters *
                                    cfg.initialNetworksPerRouter) match {
                    case UPDATE => updateNetwork(br)
                    case REMOVAL => br.delete()
                    case ADDITION => addNetwork(rt)
                }
            case 2 =>
                log.info("updating tmpPorts") // this also may update port groups
                val p = getRandomEntry(tmpPorts)
                chooseOperation(tmpPorts.size, cfg.initialPortsPerNetwork) match {
                    case UPDATE => updatePort(p)
                    case REMOVAL => p.delete()
                    case ADDITION => fixedNetwork.createPort()
                }
            case 3 =>
                log.info("updating tmpVteps")
                val vt = getRandomEntry(tmpVteps)
                chooseOperation(tmpVteps.size, cfg.initialVteps) match {
                    case UPDATE => updateVtep(vt)
                    case REMOVAL => rmVtep(vt)
                    case ADDITION => addVtep()
                }
            case 4 =>
                log.info("updating tmpHosts")
                val h = getRandomEntry(tmpHosts)
                chooseOperation(tmpHosts.size, cfg.initialHosts) match {
                    case UPDATE => updateHost(h)
                    case REMOVAL => rmHost(h)
                    case ADDITION => addHost()
                }
        }
    }
}

/**
 * Common operations for all topology objects
 */
class TopologyEntity(protected var proto: Message)
                    (implicit val storage: StorageWithOwnership) {
    val idField = proto.getDescriptorForType.findFieldByName("id")
    val nameField = proto.getDescriptorForType.findFieldByName("name")
    def create(): this.type = {storage.create(proto); this}
    def create(ownership: UUID): this.type =
        {storage.create(proto, ownership); this}
    def update(): this.type = {storage.update(proto); this}
    def update(ownership: UUID): this.type =
        {storage.update(proto, ownership, null); this}
    def delete(): Unit = {
        storage.delete(proto.getClass, proto.getField(idField))
    }
    def delete(ownership: UUID): Unit = {
        storage.delete(proto.getClass, proto.getField(idField), ownership)
    }

    protected def getId[I](k: Class[I]): I =
        proto.getField(idField).asInstanceOf[I]

    def getName: String = if (nameField == null) ""
        else proto.getField(nameField).asInstanceOf[String]

    protected def clearField(f: String): this.type = {
        val field = proto.getDescriptorForType.findFieldByName(f)
        proto = proto.toBuilder.clearField(field).build()
        this
    }
    protected def setField(f: String, v: Any): this.type = {
        val field = proto.getDescriptorForType.findFieldByName(f)
        proto = proto.toBuilder.setField(field, v).build()
        this
    }
    protected def getRepeatedField[T](f: String, k: Class[T]): Iterable[T] = {
        val field = proto.getDescriptorForType.findFieldByName(f)
        if (field != null) {
            proto.getField(field).asInstanceOf[Iterable[T]]
        } else {
            List()
        }
    }
    protected def setRepeatedField(f: String, l: Iterable[AnyRef]): this.type = {
        val field = proto.getDescriptorForType.findFieldByName(f)
        val builder = proto.toBuilder.clearField(field)
        for (v <- l) {
            builder.addRepeatedField(field, v)
        }
        proto = builder.build()
        this
    }
}
object TopologyEntity {
    def getProto[T](k: Class[T], id: AnyRef)
                   (implicit storage: StorageWithOwnership): Option[T] =
        storage.get(k, id).map(Some(_)).recover({case _ => None}).await(Duration.Inf)
    def getAllProtos[T](k: Class[T])(implicit storage: StorageWithOwnership)
        : Iterable[T] = storage.getAll(k).await(Duration.Inf)
}

/**
 * Topology object with admin state
 */
abstract class TopologyEntityWithAdminState(p: Message)
    (implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    private val adminStateField =
        proto.getDescriptorForType.findFieldByName("admin_state_up")
    def getAdminStateUp: Boolean =
        proto.getField(adminStateField).asInstanceOf[Boolean]
    def setAdminStateUp(v: Boolean): this.type =
        setField("admin_state_up", v).update()
}

/**
 * Virtual Switching device model (i.e. Routers and Networks)
 * Note: the name is not the best one...
 */
abstract class VSwitch(p: Message)(implicit storage: StorageWithOwnership)
    extends TopologyEntityWithAdminState(p) {
    def createPort(): Port
    def removePort(port: Port): this.type
}

/**
 * Port model
 */
object Port {
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Port] =
        getProto(classOf[Topology.Port], id).map(new Port(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Port] =
        getAllProtos(classOf[Topology.Port]).map(new Port(_))
}
class Port(p: Topology.Port)(implicit storage: StorageWithOwnership)
    extends TopologyEntityWithAdminState(p) {
    def this(rt: Router)(implicit storage: StorageWithOwnership) =
        this(Topology.Port.newBuilder()
                 .setId(randomId).setRouterId(rt.getId).build)
    def this(nw: Network)(implicit storage: StorageWithOwnership) =
        this(Topology.Port.newBuilder()
                 .setId(randomId).setNetworkId(nw.getId).build)

    def model = proto.asInstanceOf[Topology.Port]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        linkTo(null)
        setHost(null)
        getPortGroups.foreach({_.removePort(this)})
        getDevice.foreach({_.removePort(this)})
        super.delete()
    }

    def setHost(h: Host): Port = {
        if (model.hasHostId) {
            Host.get(model.getHostId) foreach {_.removePort(this)}
        }
        if (h == null) {
            getVtepBindings.foreach({_.delete()})
            clearField("host_id")
            this
        } else {
            h.addPort(this)
            setField("host_id", h.getId)
            update()
        }
    }

    def getVtepBindings: Iterable[VtepBinding] = {
        if (model.hasVtepId) {
            Vtep.get(model.getVtepId) match {
                case None => List()
                case Some(vtep) =>
                    vtep.getBindings filter {_.networkId == model.getNetworkId}
            }
        } else {
            List()
        }
    }

    // Get associated device (Network or Router)
    def getDevice: Option[VSwitch] = {
        if (model.hasRouterId) {
            Router.get(model.getRouterId)
        } else if (model.hasNetworkId) {
            Network.get(model.getNetworkId)
        } else {
            None
        }
    }

    // Get associated port groups
    def getPortGroups: Iterable[PortGroup] =
        model.getPortGroupIdsList flatMap {PortGroup.get(_)}

    def addPortGroup(pg: PortGroup): Port = {
        setRepeatedField("port_group_ids",
                         model.getPortGroupIdsList.toSet + pg.getId)
        update()
    }

    def removePortGroup(pg: PortGroup): Port = {
        setRepeatedField("port_group_ids",
                         model.getPortGroupIdsList.toSet - pg.getId)
        update()
    }

    // Add back-references to tmpPorts linked to this one
    private def addRemotePort(p: Port): Port =
        setRepeatedField("port_ids", model.getPortIdsList.toSet + p.getId)
            .update()

    // Link this port to a port in a target device and remove previous
    // links, if any. Set to null to unlink
    def linkTo(p: Port): Port = {
        if (model.hasPeerId) {
            Port.get(model.getPeerId) foreach {_.delete()}
        }
        if (p != null) {
            p.addRemotePort(this)
            setField("peer_id", p.getId)
            update()
        } else {
            clearField("peer_id")
            this
        }
    }

    // Get back-references to tmpPorts linked to this one
    def getRemotePorts: Iterable[Port] =
        model.getPortIdsList flatMap {Port.get(_)}

    // Get the target port to which this one is linked
    def getTargetPort: Option[Port] =
        if (model.getPeerId != null) Port.get(model.getPeerId) else None

    // Get the devices linked to this one
    def getRemoteDevices: Iterable[VSwitch] =
        getRemotePorts flatMap {_.getDevice}

    // Get the device to which this port is linked
    def getTargetDevice: Option[VSwitch] =
        getTargetPort flatMap {_.getDevice}

    // Set VxLan parameters
    def setVxLanAttributes(vtep: Vtep): Port = {
        setField("vtep_id", vtep.getId)
        this
    }

    def matchVtepBinding(binding: VtepBinding): Boolean =
        binding.vtepId == model.getVtepId &&
        binding.networkId == model.getNetworkId
}

/**
 * Router model
 */
object Router {
    def apply(name: String)(implicit storage: StorageWithOwnership): Router =
        new Router(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Router] =
        getProto(classOf[Topology.Router], id).map(new Router(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Router] =
        getAllProtos(classOf[Topology.Router]).map(new Router(_))
}
class Router(p: Topology.Router)(implicit storage: StorageWithOwnership)
    extends VSwitch(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.Router.newBuilder().setId(randomId).setName(name).build)
    def model = proto.asInstanceOf[Topology.Router]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getRemoteDevices.foreach({_.delete()})
        getPorts.foreach({_.delete()})
        super.delete()
    }

    // create an attached port
    override def createPort(): Port = {
        val port = new Port(this).create()
        setRepeatedField("port_ids", model.getPortIdsList.toSet + port.getId)
            .update()
        port
    }
    // Remove the reference to an attached port
    override def removePort(p: Port): this.type =
        setRepeatedField("port_ids", model.getPortIdsList.toSet - p.getId)
            .update()

    // Link this router to another router
    def linkTo(rt: Router): Router = {
        createPort().linkTo(rt.createPort())
        this
    }

    // Get attached tmpPorts
    def getPorts: Iterable[Port] = model.getPortIdsList flatMap {Port.get(_)}

    // Get devices linked to this router
    def getRemoteDevices: Iterable[VSwitch] =
        getPorts.flatMap({_.getRemoteDevices})
}


/**
 * Network model
 */
object Network {
    def apply(name: String)(implicit storage: StorageWithOwnership) =
        new Network(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Network] =
        getProto(classOf[Topology.Network], id).map(new Network(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Network] =
        getAllProtos(classOf[Topology.Network]).map(new Network(_))
}
class Network(p: Topology.Network)(implicit storage: StorageWithOwnership)
    extends VSwitch(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.Network.newBuilder().setId(randomId).setName(name).build)
    def model = proto.asInstanceOf[Topology.Network]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        vtepBindings.foreach({_.delete()})
        getRemoteDevices.foreach({_.delete()})
        getPorts.foreach({_.delete()})
        super.delete()
    }

    // create an attached port
    override def createPort(): Port = {
        val port = new Port(this).create()
        setRepeatedField("port_ids", model.getPortIdsList.toSet + port.getId)
            .update()
        port
    }

    // remove the reference to an attached port
    override def removePort(p: Port): this.type =
        setRepeatedField("port_ids", model.getPortIdsList.toSet - p.getId)
            .update()

    // Link this network to a router
    def linkTo(rt: Router): Network = {
        createPort().linkTo(rt.createPort())
        this
    }

    // Get attached tmpPorts
    // Note: we remove vxlan tmpPorts for convenience, as the updater
    // treats them differently from the other tmpPorts
    def getPorts: Iterable[Port] =
        (model.getPortIdsList.toSet --
            model.getVxlanPortIdsList.toSet) flatMap {Port.get(_)}

    // Get devices linked to this network
    def getRemoteDevices: Iterable[VSwitch] =
        getPorts.flatMap({_.getRemoteDevices})

    // get the list of vxlan tmpPorts
    def getVxLanPorts: Iterable[Port] =
        model.getVxlanPortIdsList flatMap {Port.get(_)}

    // Create vtep binding
    def bindVtep(vtep: Vtep, h: Host): Network = {
        val binding = VtepBinding(this, vtep).create()
        val port = new Port(this)
            .setVxLanAttributes(vtep).create()
            .setHost(h)
        vtep.addBinding(binding)
        setRepeatedField("port_ids",
                         model.getPortIdsList.toSet + port.getId)
        setRepeatedField("vxlan_port_ids",
                         model.getVxlanPortIdsList.toSet + port.getId)
        update()
    }

    // Remove vtep binding
    def removeVtepBinding(binding: VtepBinding): Network = {
        val ports = getVxLanPorts.filter({_.matchVtepBinding(binding)})
        ports.foreach {_.delete()}
        setRepeatedField("port_ids",
                         model.getPortIdsList.toSet --
                             ports.map(_.getId).toSet)
        setRepeatedField("vxlan_port_ids",
                         model.getVxlanPortIdsList.toSet --
                             ports.map(_.getId).toSet)
        update()
    }

    def vtepBindings: Iterable[VtepBinding] =
        VtepBinding.getAll.filter(_.networkId == getId)

}

/**
 * Tunnel zone model
 */
object TunnelZone {
    def apply(name: String, t: Topology.TunnelZone.Type)
             (implicit storage: StorageWithOwnership): TunnelZone =
        new TunnelZone(name, t)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[TunnelZone] =
        getProto(classOf[Topology.TunnelZone], id).map(new TunnelZone(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[TunnelZone] =
        getAllProtos(classOf[Topology.TunnelZone]).map(new TunnelZone(_))
}
class TunnelZone(p: Topology.TunnelZone)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(name: String, t: Topology.TunnelZone.Type)
            (implicit storage: StorageWithOwnership) =
        this(Topology.TunnelZone.newBuilder()
                 .setId(randomId).setName(name).setType(t).build())
    def model = proto.asInstanceOf[Topology.TunnelZone]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getHosts foreach {_.removeTunnelZone(this)}
        super.delete()
    }

    private def makeHostToIp(h: Host): Topology.TunnelZone.HostToIp =
        Topology.TunnelZone.HostToIp.newBuilder()
            .setHostId(h.getId)
            .setIp(IPAddressUtil.toProto(h.address))
            .build()

    def addHost(h: Host): TunnelZone = {
        h.addTunnelZone(this)
        setRepeatedField("hosts",
            model.getHostsList.filterNot({_.getHostId == h.getId}).toSet +
            makeHostToIp(h))
        setRepeatedField("host_ids",
                         model.getHostIdsList.filterNot({_ == h.getId}).toSet +
                             h.getId)
        update()
    }

    def removeHost(h: Host): TunnelZone = {
        h.removeTunnelZone(this)
        setRepeatedField("hosts",
                         model.getHostsList.filterNot({_.getHostId == h.getId}))
        setRepeatedField("host_ids",
                         model.getHostIdsList.filterNot({_ == h.getId}))
        update()
    }

    def getHosts: Iterable[Host] =
        model.getHostIdsList flatMap {Host.get(_)}
}

/**
 * Vtep model
 */
object Vtep {
    def apply(tzName: String)(implicit storage: StorageWithOwnership): Vtep =
        new Vtep(tzName)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Vtep] =
        getProto(classOf[Topology.Vtep], id).map(new Vtep(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Vtep] =
        getAllProtos(classOf[Topology.Vtep]).map(new Vtep(_))
}
class Vtep(p: Topology.Vtep)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(tzName: String)(implicit storage: StorageWithOwnership) =
        this(Topology.Vtep.newBuilder()
                 .setId(randomId)
                 .setManagementIp(IPAddressUtil.toProto(randomIp))
                 .setManagementPort(6632)
                 .addTunnelIps(randomIp)
                 .setTunnelZoneId(
                    TunnelZone(tzName, Topology.TunnelZone.Type.VTEP)
                        .create().getId)
                 .build())
    def model = proto.asInstanceOf[Topology.Vtep]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getBindings foreach {_.delete()}
        tunnelZone.delete()
        super.delete()
    }

    def tunnelZone: TunnelZone = TunnelZone.get(model.getTunnelZoneId).get
    def mgmtIp: Commons.IPAddress = model.getManagementIp
    def mgmtPort: Int = model.getManagementPort
    def tunnelIp: String = model.getTunnelIps(0)

    def addBinding(binding: VtepBinding): Vtep = {
        setRepeatedField("bindings",
                         model.getBindingsList.toSet + binding.getId)
        update()
    }
    def removeBinding(binding: VtepBinding): Vtep = {
        setRepeatedField("bindings",
                         model.getBindingsList.toSet - binding.getId)
        update()
    }

    def getBindings: Iterable[VtepBinding] =
        model.getBindingsList flatMap {VtepBinding.get(_)}
}

/**
 * Vtep binding model
 */
object VtepBinding {
    def apply(nw: Network, vtep: Vtep)(implicit storage: StorageWithOwnership)
        : VtepBinding =
        new VtepBinding(nw, vtep)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[VtepBinding] =
        getProto(classOf[Topology.VtepBinding], id).map(new VtepBinding(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[VtepBinding] =
        getAllProtos(classOf[Topology.VtepBinding]).map(new VtepBinding(_))
}
class VtepBinding(p: Topology.VtepBinding)
                 (implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(nw: Network, vtep: Vtep)(implicit storage: StorageWithOwnership) =
        this(Topology.VtepBinding.newBuilder()
                 .setId(randomId)
                 .setNetworkId(nw.getId)
                 .setVlanId(10000 + random.nextInt(8192))
                 .setVtepId(vtep.getId)
                 .setPortName("port" + random.nextInt())
                 .build())
    def model = proto.asInstanceOf[Topology.VtepBinding]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        Vtep.get(vtepId) foreach {_.removeBinding(this)}
        Network.get(networkId) foreach {_.removeVtepBinding(this)}
        super.delete()
    }

    def vlanId: Int = model.getVlanId
    def vtepId: Commons.UUID = model.getVtepId
    def networkId: Commons.UUID = model.getNetworkId
}

/**
 * Host model
 */
object Host {
    def apply(name: String)(implicit storage: StorageWithOwnership)
        : Host = new Host(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Host] =
        getProto(classOf[Topology.Host], id).map(new Host(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Host] =
        getAllProtos(classOf[Topology.Host]).map(new Host(_))
}
class Host(p: Topology.Host)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.Host.newBuilder()
                 .setId(randomId)
                 .addAddresses(IPAddressUtil.toProto(randomIp))
                 .setName(name).build())
    def model = proto.asInstanceOf[Topology.Host]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def create(): this.type = super.create(owner)
    override def update(): this.type = super.update(owner)
    override def delete(): Unit = {
        getTunnelZones foreach {_.removeHost(this)}
        getPorts foreach {_.setHost(null)}
        super.delete(owner)
    }

    def address: String =
        IPAddressUtil.toIPAddr(model.getAddressesList.get(0)).toString

    def addTunnelZone(tz: TunnelZone): Host = {
        setRepeatedField("tunnel_zone_ids",
                         model.getTunnelZoneIdsList.toSet + tz.getId)
        update(owner)
    }

    def removeTunnelZone(tz: TunnelZone): Host = {
        setRepeatedField("tunnel_zone_ids",
                         model.getTunnelZoneIdsList.toSet - tz.getId)
        update(owner)
    }

    def getTunnelZones: Iterable[TunnelZone] =
        model.getTunnelZoneIdsList flatMap {TunnelZone.get(_)}

    def addPort(p: Port): Host = {
        setRepeatedField("port_bindings",
            model.getPortBindingsList
                .filterNot({_.getPortId == p.getId}).toSet +
            PortBinding.newBuilder()
                .setPortId(p.getId).setInterfaceName("if0").build())
        update(owner)
    }

    def removePort(p: Port): Host = {
        setRepeatedField("port_bindings",
            model.getPortBindingsList
                .filterNot({_.getPortId == p.getId}))
        update(owner)
    }

    def getPorts: Iterable[Port] =
        model.getPortBindingsList map {_.getPortId} flatMap {Port.get(_)}
}

/**
 * Port group model
 */
object PortGroup {
    def apply(name: String)(implicit storage: StorageWithOwnership): PortGroup =
        new PortGroup(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[PortGroup] =
        getProto(classOf[Topology.PortGroup], id).map(new PortGroup(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[PortGroup] =
        getAllProtos(classOf[Topology.PortGroup]).map(new PortGroup(_))
}
class PortGroup(p: Topology.PortGroup)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.PortGroup.newBuilder()
                 .setId(randomId).setName(name).build())
    def model = proto.asInstanceOf[Topology.PortGroup]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getPorts.foreach {_.removePortGroup(this)}
        super.delete()
    }

    def addPort(p: Port): Unit = {
        p.addPortGroup(this)
        setRepeatedField("port_ids", model.getPortIdsList.toSet + p.getId)
        update()
    }

    def removePort(p: Port): Unit = {
        p.removePortGroup(this)
        setRepeatedField("port_ids", model.getPortIdsList.toSet - p.getId)
        update()
    }

    def getPorts: Iterable[Port] = model.getPortIdsList flatMap {Port.get(_)}
}

/**
 * IpAddress group model
 */
object IpAddrGroup {
    def apply(name: String)(implicit storage: StorageWithOwnership)
        : IpAddrGroup =
        new IpAddrGroup(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[IpAddrGroup] =
        getProto(classOf[Topology.IpAddrGroup], id).map(new IpAddrGroup(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[IpAddrGroup] =
        getAllProtos(classOf[Topology.IpAddrGroup]).map(new IpAddrGroup(_))
}
class IpAddrGroup(p: Topology.IpAddrGroup)
                 (implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.IpAddrGroup.newBuilder()
                 .setId(randomId).setName(name).build())
    def model = proto.asInstanceOf[Topology.IpAddrGroup]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    def addAddress(ip: String): IpAddrGroup = {
        setRepeatedField("ip_addr_ports",
            model.getIpAddrPortsList.filterNot(a => {
                IPAddressUtil.toIPAddr(a.getIpAddress).toString == ip }).toSet +
            IpAddrPorts.newBuilder()
                .setIpAddress(IPAddressUtil.toProto(ip)).build())
        update()
    }

    def removeAddress(ip: String): IpAddrGroup = {
        setRepeatedField("ip_addr_ports",
            model.getIpAddrPortsList.filterNot(a => {
                IPAddressUtil.toIPAddr(a.getIpAddress).toString == ip }))
        update()
    }

    def addresses: Iterable[IPAddr] =
        model.getIpAddrPortsList.map(a => {IPAddressUtil.toIPAddr(a.getIpAddress)})

}

/**
 * Chain model
 */
object Chain {
    def apply(name: String)(implicit storage: StorageWithOwnership)
        : Chain = new Chain(name)
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Chain] =
        getProto(classOf[Topology.Chain], id).map(new Chain(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Chain] =
        getAllProtos(classOf[Topology.Chain]).map(new Chain(_))
}
class Chain(p: Topology.Chain)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: StorageWithOwnership) =
        this(Topology.Chain.newBuilder().setId(randomId).setName(name).build())
    def model = proto.asInstanceOf[Topology.Chain]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}

/**
 * Route model
 */
object Route {
    def apply()(implicit storage: StorageWithOwnership): Route = new Route()
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Route] =
        getProto(classOf[Topology.Route], id).map(new Route(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Route] =
        getAllProtos(classOf[Topology.Route]).map(new Route(_))
}
class Route(p: Topology.Route)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this()(implicit storage: StorageWithOwnership) =
        this(Topology.Route.newBuilder().setId(randomId).build())
    def model = proto.asInstanceOf[Topology.Route]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}

/**
 * Rule model
 */
object Rule {
    def apply()(implicit storage: StorageWithOwnership): Rule = new Rule()
    def get(id: Commons.UUID)(implicit storage: StorageWithOwnership)
        : Option[Rule] =
        getProto(classOf[Topology.Rule], id).map(new Rule(_))
    def getAll(implicit storage: StorageWithOwnership): Iterable[Rule] =
        getAllProtos(classOf[Topology.Rule]).map(new Rule(_))
}
class Rule(p: Topology.Rule)(implicit storage: StorageWithOwnership)
    extends TopologyEntity(p) {
    def this()(implicit storage: StorageWithOwnership) =
        this(Topology.Rule.newBuilder().setId(randomId).build())
    def model = proto.asInstanceOf[Topology.Rule]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}

