package org.midonet.midolman.containers

import java.util.UUID

import scala.concurrent.{ExecutionContext, Future, Promise}

import com.google.inject.Inject
import org.slf4j.LoggerFactory
import rx.Observable

import org.midonet.cluster.models.Neutron.IPSecPolicy
import org.midonet.cluster.models.State.ContainerStatus
import org.midonet.cluster.models.{Neutron, Topology}
import org.midonet.cluster.services.MidonetBackend.ContainerHealth
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.containers.{IpsecConnection, IpsecServiceConfig, IpsecServiceContainerFunctions, IpsecServiceDef}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.packets.{IPv4Addr, MAC}


object IpSecContainerHandler {
    val defaultIKEPolicy = Neutron.IKEPolicy.newBuilder()
        .setIkeVersion(1)
        .addLifetime("3600")
        .build()

    val defaultIPSecPolicy = Neutron.IPSecPolicy.newBuilder()
        .setEncapsulationMode(IPSecPolicy.EncapsulationMode.TUNNEL)
        .setTransformProtocol(IPSecPolicy.TransformProtocol.ESP)
        .addLifetime("3600")
        .build()
}

class IpSecContainerHandler @Inject() (vt: VirtualTopology)
    extends ContainerHandler with IpsecServiceContainerFunctions {

    import IpSecContainerHandler._

    private val ipSecConfPath = ""

    private val log = LoggerFactory.getLogger("org.midonet.containers")

    private var scId: UUID =  null
    private var healthObs: Observable[ContainerStatus.Code] = null

    private implicit val ec: ExecutionContext = _

    override def create(port: ContainerPort): Future[String] = {
        if (scId != null) {
            throw new IllegalStateException(s"The handler for container $scId"+
                                            "was created more than once!")
        }
        scId = port.containerId
        vt.store.get(classOf[Topology.ServiceContainer], scId) flatMap { sc =>
            val vpnId = fromProto(sc.getConfigurationId)
            vt.store.get(classOf[Neutron.VPNService], vpnId) map { vpn =>
                watchHealth()
                startContainer(vpn, null) // TODO: <- fetch the ipsec site conn
            }
        }
    }

    private def startContainer(vpn: Neutron.VPNService,
                               siteCnxn: Neutron.IPSecSiteConnection): String = {
        val conn: IpsecConnection = new IpsecConnection(defaultIPSecPolicy,
                                                        defaultIKEPolicy,
                                                        siteCnxn)
        val conf = new IpsecServiceConfig("vpn-helper",
                                          ipSecServiceDef(vpn, siteCnxn),
                                          List(conn))
        if (!start(conf)) {
            throw new IllegalStateException(
                "Failed to start container: $scId ")
        }
        siteCnxn.getName
    }

    private def ipSecServiceDef(vpn: Neutron.VPNService,
                                siteCnxn: Neutron.IPSecSiteConnection)
    : IpsecServiceDef = {
        val name = siteCnxn.getName
        val gwIp = IPv4Addr.fromString(siteCnxn.getPeerAddress)
        IpsecServiceDef(name, ipSecConfPath, IPv4Addr.random, gwIp,
                        MAC.fromString("aa:bb:cc:dd:ee:ff"))
    }

    override def updated(port: ContainerPort): Future[Unit] = {
        if (scId == port.containerId) {
            throw new IllegalArgumentException(
                s"The handle for container $scId is told to update a port for" +
                s" a different container: $port!")
        }
        Promise[Unit].failure(new UnsupportedOperationException).future
    }

    override def delete(): Future[Unit] = {
        Promise[Unit].failure(new UnsupportedOperationException).future
    }

    override def health: Observable[ContainerStatus.Code] = {
        if (scId == null) {
            throw new IllegalStateException("The handler is not yet " +
                                            "initialized")
        }
        healthObs
    }

    /** Start watching the container's health, setting the `healthObs` property
      * so that it can be retrieved via `health`.
      */
    private def watchHealth(): Unit = {
        healthObs = vt.stateStore.keyObservable(
            classOf[Topology.ServiceContainer], scId, ContainerHealth).map {
            case "DOWN" => ContainerStatus.Code.DOWN
            case "UP" => ContainerStatus.Code.UP
            case st => {
                log.info(s"Container $scId has unknown status: $st," +
                         "probable data corruption")
            }
        }
    }
}
