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

import java.util.concurrent.{TimeUnit, Executors}

import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.models.Topology.IpAddrGroup.IpAddrPorts

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.Random

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import com.google.protobuf.Message
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.config.{ConfigLong, ConfigInt, ConfigGroup}
import org.midonet.util.functors.makeRunnable

import TopologyZoomUpdater._
import TopologyEntity._

/**
 * Topology Zoom Updater service for testing topology components.
 * It creates a set of topology elements and interconnects them.
 * Please note that this is just for testing purposes and the data
 * in the objects and the connections between them may not be
 * consistent with an actual network architecture.
 */
object TopologyZoomUpdater {
    val random = new Random()

    def randomIp: String = {
        val (b1, b2, b3, b4) = (
            1 + random.nextInt(254),
            1 + random.nextInt(254),
            1 + random.nextInt(254),
            1 + random.nextInt(254))
        s"$b1.$b2.$b3.$b4"
    }
    def randomId: Commons.UUID = UUIDUtil.randomUuidProto
}
class TopologyZoomUpdater @Inject()(implicit val storage: Storage,
                                    val cfg: TopologyZoomUpdaterConfig)
    extends AbstractService {
    private val log = LoggerFactory.getLogger(classOf[TopologyZoomUpdater])
    private val pool = Executors.newScheduledThreadPool(cfg.numThreads)

    object Operation extends Enumeration {
        type Operation = Value
        val ADDITION, REMOVAL, UPDATE = Value
    }
    import Operation._

    private val runnable: Runnable =
        makeRunnable({try {doSomething()} catch {
            case e: Throwable => log.error("failed scheduled execution", e)
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

    private var fixedVtepId: String = _
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

    /** updatable routers */
    def routers: Iterable[Router] =
        providerRouter.getRemoteDevices.collect({case r: Router => r})
            .filterNot({_.getId == fixedRouter.getId})

    /** updatable networks */
    def networks: Iterable[Network] =
        routers.flatMap({_.getRemoteDevices}).collect({case n: Network => n})

    /** updatable ports */
    def ports: Iterable[Port] =
        fixedNetwork.getPorts.filter({_.getTargetDevice == None})

    /** updatable vteps */
    private var vtepIds: Set[String] = Set()
    def vteps: Iterable[Vtep] = vtepIds flatMap {Vtep.get(_)}

    /** updatable hosts */
    private var hostIds: Set[Commons.UUID] = Set()
    def hosts: Iterable[Host] = hostIds flatMap {Host.get(_)}
    private var count: Long = 0

    @Override
    override def doStart(): Unit = {
        log.info("Starting the Topology Zoom Updater")

        try {
            buildLayout()
            if (cfg.periodMs > 0)
                pool.scheduleAtFixedRate(runnable, cfg.periodMs, cfg.periodMs,
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
        vteps foreach rmVtep
        hosts foreach rmHost
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
        for (p <- 0 to cfg.initialNetworksPerRouter) {
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
        vtepIds = vtepIds + vtep.getId
        fixedNetwork.bindVtep(vtep, fixedHost)
        vtep.tunnelZone.addHost(fixedHost)
    }

    private def addHost() = {
        count += 1
        val host = Host("host" + count).create()
        hostIds = hostIds + host.getId
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
        vtepIds = vtepIds - vt.getId
        vt.delete()
    }

    private def rmHost(h: Host) = {
        hostIds = hostIds - h.getId
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
    private def doSomething() = {
        random.nextInt(5) match {
            case 0 =>
                log.info("updating routers")
                val rt = getRandomEntry(routers)
                chooseOperation(routers.size, cfg.initialRouters) match {
                    case UPDATE => updateRouter(rt)
                    case REMOVAL => rt.delete()
                    case ADDITION => addRouter()
                }
            case 1 =>
                log.info("updating networks")
                val rt = getRandomEntry(routers)
                val br = getRandomEntry(networks)
                chooseOperation(networks.size,
                                cfg.initialRouters *
                                    cfg.initialNetworksPerRouter) match {
                    case UPDATE => updateNetwork(br)
                    case REMOVAL => br.delete()
                    case ADDITION => addNetwork(rt)
                }
            case 2 =>
                log.info("updating ports") // this also may update port groups
                val p = getRandomEntry(ports)
                chooseOperation(ports.size, cfg.initialPortsPerNetwork) match {
                    case UPDATE => updatePort(p)
                    case REMOVAL => p.delete()
                    case ADDITION => fixedNetwork.createPort()
                }
            case 3 =>
                log.info("updating vteps")
                val vt = getRandomEntry(vteps)
                chooseOperation(vteps.size, cfg.initialVteps) match {
                    case UPDATE => updateVtep(vt)
                    case REMOVAL => rmVtep(vt)
                    case ADDITION => addVtep()
                }
            case 4 =>
                log.info("updating hosts")
                val h = getRandomEntry(hosts)
                chooseOperation(hosts.size, cfg.initialHosts) match {
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
class TopologyEntity(var proto: T forSome {type T <: Message})
                    (implicit val storage: Storage) {
    val idField = proto.getDescriptorForType.findFieldByName("id")
    val nameField = proto.getDescriptorForType.findFieldByName("name")
    def create(): this.type = {storage.create(proto); this}
    def update(): this.type = {storage.update(proto); this}
    def delete(): Unit = {
        storage.delete(proto.getClass, proto.getField(idField))
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
        proto = proto.toBuilder.clearField(field).setField(field, v).build()
        this
    }
    protected def getRepeatedField[T](f: String, k: Class[T]): Iterable[T] = {
        val field = proto.getDescriptorForType.findFieldByName(f)
        if (field != null) {
            val maxIdx = proto.getRepeatedFieldCount(field) - 1
            for {idx <- 0 to maxIdx}
                yield proto.getRepeatedField(field, idx).asInstanceOf[T]
        } else {
            List[T]()
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
    def getProto[T](k: Class[T], id: AnyRef)(implicit storage: Storage):
        Option[T] = Await.result(
            storage.get(k, id).map({Some(_)}).recover({case _ => None}),
            Duration.Inf)
    def getAllProtos[T](k: Class[T])(implicit storage: Storage): Iterable[T] =
        Await.result(storage.getAll(k), Duration.Inf)
            .map({Await.result(_, Duration.Inf)})
}

/**
 * Virtual Switching device model (i.e. Routers and Networks)
 * Note: the name is not the best one...
 */
abstract class VSwitch(p: T forSome  {type T <: Message})
                      (implicit storage: Storage)
    extends TopologyEntity(p) {
    def createPort(): Port
    def removePort(p: Port): this.type
}

/**
 * Port model
 */
object Port {
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Port] =
        getProto(classOf[Topology.Port], id).map({new Port(_)})
    def getAll(implicit storage: Storage): Iterable[Port] =
        getAllProtos(classOf[Topology.Port]).map({new Port(_)})
}
class Port(p: Topology.Port)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(rt: Router)(implicit storage: Storage) =
        this(Topology.Port.newBuilder()
                 .setId(randomId).setRouterId(rt.getId).build)
    def this(nw: Network)(implicit storage: Storage) =
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

    def getAdminStateUp: Boolean = model.getAdminStateUp
    def setAdminStateUp(v: Boolean): Port =
        setField("admin_state_up", v).update()

    def setHost(h: Host): Port = {
        if (model.hasHostId) {
            Host.get(model.getHostId) foreach {_.removePort(this)}
        }
        if (h == null) {
            getVtepBindings.foreach({_.delete()})
            // NOTE. only used before deletion: we can skip it
            //clearField("host_id")
            this
        } else {
            h.addPort(this)
            setField("host_id", h.getId)
            update()
        }
    }

    def getVtepBindings: Iterable[VtepBinding] = {
        if (model.hasVtepMgmtIp) {
            Vtep.get(IPAddressUtil.toIPAddr(model.getVtepMgmtIp).toString) match {
                case None => List[VtepBinding]()
                case Some(vtep) =>
                    vtep.getBindings filter {_.vlanId == model.getVtepVni}
            }
        } else {
            List[VtepBinding]()
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

    // Add back-references to ports linked to this one
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
            // TODO: the following line seems to interfere with zoom
            // declared bindings, so commenting it out by now
            // (anyway, we just set the linked port to null before deletion,
            // so this is kinda redundant...
            //clearField("peer_id")
            this
        }
    }

    // Get back-references to ports linked to this one
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
    def setVxLanAttributes(vtep: Vtep, vni: Int): Port = {
        setField("vtep_mgmt_ip", IPAddressUtil.toProto(vtep.getId))
        setField("vtep_mgmt_port", vtep.mgmtPort)
        setField("vtep_tunnel_ip", IPAddressUtil.toProto(vtep.tunnelIp))
        setField("vtep_tunnel_zone_id", vtep.tunnelZone.getId)
        setField("vtep_vni", vni)
        this
    }

    def matchVtepBinding(binding: VtepBinding): Boolean =
        binding.mgmtIp == model.getVtepMgmtIp.getAddress &&
        binding.vlanId == model.getVtepVni
}

/**
 * Router model
 */
object Router {
    def apply(name: String)(implicit storage: Storage): Router =
        new Router(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Router] =
        getProto(classOf[Topology.Router], id).map({new Router(_)})
    def getAll(implicit storage: Storage): Iterable[Router] =
        getAllProtos(classOf[Topology.Router]).map({new Router(_)})
}
class Router(p: Topology.Router)(implicit storage: Storage)
    extends VSwitch(p) {
    def this(name: String)(implicit storage: Storage) =
        this(Topology.Router.newBuilder().setId(randomId).setName(name).build)
    def model = proto.asInstanceOf[Topology.Router]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getRemoteDevices.foreach({_.delete()})
        getPorts.foreach({_.delete()})
        super.delete()
    }

    def getAdminStateUp: Boolean = model.getAdminStateUp
    def setAdminStateUp(v: Boolean): Router =
        setField("admin_state_up", v).update()

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

    // Get attached ports
    def getPorts: Iterable[Port] = model.getPortIdsList flatMap {Port.get(_)}

    // Get devices linked to this router
    def getRemoteDevices: Iterable[VSwitch] =
        getPorts.flatMap({_.getRemoteDevices})
}


/**
 * Network model
 */
object Network {
    def apply(name: String)(implicit storage: Storage) = new Network(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Network] =
        getProto(classOf[Topology.Network], id).map({new Network(_)})
    def getAll(implicit storage: Storage): Iterable[Network] =
        getAllProtos(classOf[Topology.Network]).map({new Network(_)})
}
class Network(p: Topology.Network)(implicit storage: Storage)
    extends VSwitch(p) {
    def this(name: String)(implicit storage: Storage) =
        this(Topology.Network.newBuilder().setId(randomId).setName(name).build)
    def model = proto.asInstanceOf[Topology.Network]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        vtepBindings.foreach({_.delete()})
        getRemoteDevices.foreach({_.delete()})
        getPorts.foreach({_.delete()})
        super.delete()
    }

    def getAdminStateUp: Boolean = model.getAdminStateUp
    def setAdminStateUp(v: Boolean): Network =
        setField("admin_state_up", v).update()

    // create an attached port
    override def createPort(): Port = {
        val port = new Port(this).create()
        setRepeatedField("port_ids", model.getPortIdsList.toSet + port.getId)
        // No need to update, as a zoom binding does it automagically
        //    .update()
        port
    }

    // remove the reference to an attached port
    override def removePort(p: Port): this.type =
        setRepeatedField("port_ids", model.getPortIdsList.toSet - p.getId)
        // No need to update, as a zoom binding does it automagically
        //    .update()

    // Link this network to a router
    def linkTo(rt: Router): Network = {
        createPort().linkTo(rt.createPort())
        this
    }

    // Get attached ports
    // Note: we remove vxlan ports for convenience, as the updater
    // treats them differently from the other ports
    def getPorts: Iterable[Port] =
        (model.getPortIdsList.toSet --
            model.getVxlanPortIdsList.toSet) flatMap {Port.get(_)}

    // Get devices linked to this network
    def getRemoteDevices: Iterable[VSwitch] =
        getPorts.flatMap({_.getRemoteDevices})

    // get the list of vxlan ports
    private def getVxLanPorts: Iterable[Port] =
        model.getVxlanPortIdsList flatMap {Port.get(_)}
    
    // Create vtep binding
    def bindVtep(vtep: Vtep, h: Host): Network = {
        val binding = VtepBinding(this, vtep).create()
        val port = new Port(this)
            .setVxLanAttributes(vtep, binding.vlanId).create()
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
                             ports.map({_.getId}).toSet)
        setRepeatedField("vxlan_port_ids",
                         model.getVxlanPortIdsList.toSet --
                             ports.map({_.getId}).toSet)
        update()
    }

    def vtepBindings: Iterable[VtepBinding] =
        VtepBinding.getAll.filter({_.networkId == getId})

}

/**
 * Tunnel zone model
 */
object TunnelZone {
    def apply(name: String, t: Topology.TunnelZone.Type)
             (implicit storage: Storage): TunnelZone = new TunnelZone(name, t)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[TunnelZone] =
        getProto(classOf[Topology.TunnelZone], id).map({new TunnelZone(_)})
    def getAll(implicit storage: Storage): Iterable[TunnelZone] =
        getAllProtos(classOf[Topology.TunnelZone]).map({new TunnelZone(_)})
}
class TunnelZone(p: Topology.TunnelZone)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(name: String, t: Topology.TunnelZone.Type)
            (implicit storage: Storage) =
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
        update()
    }

    def removeHost(h: Host): TunnelZone = {
        h.removeTunnelZone(this)
        setRepeatedField("hosts",
                         model.getHostsList.filterNot({_.getHostId == h.getId}))
        update()
    }

    def getHosts: Iterable[Host] =
        model.getHostsList map {_.getHostId} flatMap {Host.get(_)}
}

/**
 * Vtep model
 */
object Vtep {
    def apply(tzName: String)(implicit storage: Storage): Vtep =
        new Vtep(tzName)
    def get(id: String)(implicit storage: Storage): Option[Vtep] =
        getProto(classOf[Topology.Vtep], id).map({new Vtep(_)})
    def getAll(implicit storage: Storage): Iterable[Vtep] =
        getAllProtos(classOf[Topology.Vtep]).map({new Vtep(_)})
}
class Vtep(p: Topology.Vtep)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(tzName: String)(implicit storage: Storage) =
        this(Topology.Vtep.newBuilder()
                 .setId(randomIp)
                 .setManagementPort(6632)
                 .addTunnelIps(randomIp)
                 .setTunnelZoneId(
                    TunnelZone(tzName, Topology.TunnelZone.Type.VTEP)
                        .create().getId)
                 .build())
    def model = proto.asInstanceOf[Topology.Vtep]
    def getId: String = getId(classOf[String])

    override def delete(): Unit = {
        getBindings foreach {_.delete()}
        tunnelZone.delete()
        super.delete()
    }

    def tunnelZone: TunnelZone = TunnelZone.get(model.getTunnelZoneId).get
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
    def apply(nw: Network, vtep: Vtep)(implicit storage: Storage): VtepBinding =
        new VtepBinding(nw, vtep)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[VtepBinding] =
        getProto(classOf[Topology.VtepBinding], id).map({new VtepBinding(_)})
    def getAll(implicit storage: Storage): Iterable[VtepBinding] =
        getAllProtos(classOf[Topology.VtepBinding]).map({new VtepBinding(_)})
}
class VtepBinding(p: Topology.VtepBinding)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(nw: Network, vtep: Vtep)(implicit storage: Storage) =
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
        Vtep.get(mgmtIp) foreach {_.removeBinding(this)}
        Network.get(networkId) foreach {_.removeVtepBinding(this)}
        super.delete()
    }
    
    def vlanId: Int = model.getVlanId
    def mgmtIp: String = model.getVtepId
    def networkId: Commons.UUID = model.getNetworkId
}

/**
 * Host model
 */
object Host {
    def apply(name: String)(implicit storage: Storage): Host = new Host(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Host] =
        getProto(classOf[Topology.Host], id).map({new Host(_)})
    def getAll(implicit storage: Storage): Iterable[Host] =
        getAllProtos(classOf[Topology.Host]).map({new Host(_)})
}
class Host(p: Topology.Host)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: Storage) =
        this(Topology.Host.newBuilder()
                 .setId(randomId)
                 .addAddresses(IPAddressUtil.toProto(randomIp))
                 .setName(name).build())
    def model = proto.asInstanceOf[Topology.Host]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])

    override def delete(): Unit = {
        getTunnelZones foreach {_.removeHost(this)}
        getPorts foreach {_.setHost(null)}
        super.delete()
    }

    def address: String =
        IPAddressUtil.toIPAddr(model.getAddressesList.get(0)).toString

    def addTunnelZone(tz: TunnelZone): Host = {
        setRepeatedField("tunnel_zone_ids",
                         model.getTunnelZoneIdsList.toSet + tz.getId)
        update()
    }

    def removeTunnelZone(tz: TunnelZone): Host = {
        setRepeatedField("tunnel_zone_ids",
                         model.getTunnelZoneIdsList.toSet - tz.getId)
        update()
    }

    def getTunnelZones: Iterable[TunnelZone] =
        model.getTunnelZoneIdsList flatMap {TunnelZone.get(_)}

    def addPort(p: Port): Host = {
        setRepeatedField("port_interface_mapping",
            model.getPortInterfaceMappingList
                .filterNot({_.getPortId == p.getId}).toSet +
            PortToInterface.newBuilder()
                .setPortId(p.getId).setInterfaceName("if0").build())
        update()
    }

    def removePort(p: Port): Host = {
        setRepeatedField("port_interface_mapping",
            model.getPortInterfaceMappingList
                .filterNot({_.getPortId == p.getId}))
        update()
    }

    def getPorts: Iterable[Port] =
        model.getPortInterfaceMappingList map {_.getPortId} flatMap {Port.get(_)}
}

/**
 * Port group model
 */
object PortGroup {
    def apply(name: String)(implicit storage: Storage): PortGroup =
        new PortGroup(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[PortGroup] =
        getProto(classOf[Topology.PortGroup], id).map({new PortGroup(_)})
    def getAll(implicit storage: Storage): Iterable[PortGroup] =
        getAllProtos(classOf[Topology.PortGroup]).map({new PortGroup(_)})
}
class PortGroup(p: Topology.PortGroup)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: Storage) =
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
    def apply(name: String)(implicit storage: Storage): IpAddrGroup =
        new IpAddrGroup(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[IpAddrGroup] =
        getProto(classOf[Topology.IpAddrGroup], id).map({new IpAddrGroup(_)})
    def getAll(implicit storage: Storage): Iterable[IpAddrGroup] =
        getAllProtos(classOf[Topology.IpAddrGroup]).map({new IpAddrGroup(_)})
}
class IpAddrGroup(p: Topology.IpAddrGroup)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: Storage) =
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

}

/**
 * Chain model
 */
object Chain {
    def apply(name: String)(implicit storage: Storage): Chain = new Chain(name)
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Chain] =
        getProto(classOf[Topology.Chain], id).map({new Chain(_)})
    def getAll(implicit storage: Storage): Iterable[Chain] =
        getAllProtos(classOf[Topology.Chain]).map({new Chain(_)})
}
class Chain(p: Topology.Chain)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this(name: String)(implicit storage: Storage) =
        this(Topology.Chain.newBuilder().setId(randomId).setName(name).build())
    def model = proto.asInstanceOf[Topology.Chain]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}

/**
 * Route model
 */
object Route {
    def apply()(implicit storage: Storage): Route = new Route()
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Route] =
        getProto(classOf[Topology.Route], id).map({new Route(_)})
    def getAll(implicit storage: Storage): Iterable[Route] =
        getAllProtos(classOf[Topology.Route]).map({new Route(_)})
}
class Route(p: Topology.Route)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this()(implicit storage: Storage) =
        this(Topology.Route.newBuilder().setId(randomId).build())
    def model = proto.asInstanceOf[Topology.Route]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}

/**
 * Rule model
 */
object Rule {
    def apply()(implicit storage: Storage): Rule = new Rule()
    def get(id: Commons.UUID)(implicit storage: Storage): Option[Rule] =
        getProto(classOf[Topology.Rule], id).map({new Rule(_)})
    def getAll(implicit storage: Storage): Iterable[Rule] =
        getAllProtos(classOf[Topology.Rule]).map({new Rule(_)})
}
class Rule(p: Topology.Rule)(implicit storage: Storage)
    extends TopologyEntity(p) {
    def this()(implicit storage: Storage) =
        this(Topology.Rule.newBuilder().setId(randomId).build())
    def model = proto.asInstanceOf[Topology.Rule]
    def getId: Commons.UUID = getId(classOf[Commons.UUID])
}


/**
 * Configuration
 */
object TopologyZoomUpdaterConfig {
    final val NROUTERS = 4
    final val NBRIDGES = 4
    final val NPORTS = 4
    final val NVTEPS = 4
    final val NHOSTS = 4
    final val DEFAULT_NUMTHREADS = 1
    final val DEFAULT_INTERVAL = 0
}

/** Configuration for the Topology Tester */
@ConfigGroup("topology_zoom_updater")
trait TopologyZoomUpdaterConfig {
    import TopologyZoomUpdaterConfig._

    /** Number of threads to use for the scheduled updates */
    @ConfigInt(key = "num_threads", defaultValue = DEFAULT_NUMTHREADS)
    def numThreads: Int

    /** Interval of time between updates; if set to 0, the database is
      * populated statically and no updates are performed. */
    @ConfigLong(key = "period_ms", defaultValue = DEFAULT_INTERVAL)
    def periodMs: Long

    /** Initial number of routers, apart from the provider router */
    @ConfigInt(key = "initial_routers", defaultValue = NROUTERS)
    def initialRouters: Int

    /** Initial number of networks per router */
    @ConfigInt(key = "initial_networks_per_router", defaultValue = NBRIDGES)
    def initialNetworksPerRouter: Int

    /** Initial number of ports per network */
    @ConfigInt(key = "initial_ports_per_network", defaultValue = NPORTS)
    def initialPortsPerNetwork: Int

    /** Initial number of vteps */
    @ConfigInt(key = "initial_vteps", defaultValue = NVTEPS)
    def initialVteps: Int

    /** Initial number of hosts */
    @ConfigInt(key = "initial_hosts", defaultValue = NHOSTS)
    def initialHosts: Int
}

