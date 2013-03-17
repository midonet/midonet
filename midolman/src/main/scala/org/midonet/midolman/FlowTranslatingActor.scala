// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import scala.collection.JavaConverters._
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.collection.{Set => ROSet}
import akka.actor.Actor
import akka.dispatch.{Promise, Future}
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout
import java.util.UUID

import org.midonet.midolman.DatapathController.EgressPortSetChainPacketContext
import org.midonet.midolman.datapath.{FlowActionOutputToVrnPort,
        FlowActionOutputToVrnPortSet}
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.simulation.{Bridge => RCUBridge, Chain}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.midolman.topology.VirtualTopologyActor.{
        BridgeRequest, ChainRequest, PortRequest}
import org.midonet.midolman.topology.rcu.PortSet
import org.midonet.midolman.topology.{
        FlowTagger, VirtualTopologyActor, VirtualToPhysicalMapper}
import org.midonet.cluster.client
import org.midonet.cluster.client.ExteriorPort
import org.midonet.odp.flows.{
        FlowActionUserspace, FlowKeys, FlowActions, FlowAction}
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}


trait FlowTranslatingActor extends Actor {
    protected val datapathConnection: OvsDatapathConnection
    protected val dpState: DatapathState
    val log: LoggingAdapter

    private implicit val requestReplyTimeout = new Timeout(1 second)

    import context._

    protected def translateVirtualWildcardFlow(
            flow: WildcardFlow, tags: ROSet[Any] = Set.empty):
                Future[Tuple2[WildcardFlow, ROSet[Any]]] = {
        val flowMatch = flow.getMatch
        val inPortId = flowMatch.getInputPortUUID

        // tags can be null
        val dpTags = new mutable.HashSet[Any]
        if (tags != null)
            dpTags ++= tags

        dpState.vportResolver.getDpPortNumberForVport(inPortId) match {
            case Some(portNo) =>
                flowMatch.setInputPortNumber(portNo.shortValue())
                         .unsetInputPortUUID()
                dpTags += FlowTagger.invalidateDPPort(portNo.shortValue())
            case None =>
        }

        val actions = Option(flow.getActions) map {_.asScala} getOrElse Seq.empty

        val translatedFuture = translateActions(
                actions, Option(inPortId), Option(dpTags), flow.getMatch)

        translatedFuture map {Option(_)} fallbackTo { Promise.successful(None) } map {
            case None =>
                flow.setActions(Nil)
                flow.setHardExpirationMillis(5000)
                (flow, dpTags)
            case Some(translated) =>
                flow.setActions(translated.asJava)
                (flow, dpTags)
        }
    }

    protected def portsForLocalPorts(localVrnPorts: Seq[UUID]): Seq[Short] = {
        localVrnPorts flatMap {
            dpState.vportResolver.getDpPortNumberForVport(_) match {
                case Some(value) => Some(value.shortValue())
                case None =>
                    // TODO(pino): log that the port number was not found.
                    None
            }
        }
    }

    protected def applyOutboundFilters[A](localPorts: Seq[client.Port[_]],
                                        portSetID: UUID,
                                        pktMatch: WildcardMatch,
                                        thunk: Seq[UUID] => Future[A]): Future[A] = {
        // Fetch all of the chains.
        val chainFutures = localPorts map { port =>
            if (port.outFilterID == null)
                Promise.successful(null)
            else
                ask(VirtualTopologyActor.getRef,
                    ChainRequest(port.outFilterID, update = false)).mapTo[Chain]
        }
        // Apply the chains.
        Future.sequence(chainFutures) flatMap {
            chains =>
                val egressPorts = (localPorts zip chains) filter { portchain =>
                    val port = portchain._1
                    val chain = portchain._2
                    val fwdInfo = new EgressPortSetChainPacketContext(port.id)

                    // apply chain and check result is ACCEPT.
                    val result =
                        Chain.apply(chain, fwdInfo, pktMatch, port.id, true)
                            .action
                    if (result != RuleResult.Action.ACCEPT &&
                        result != RuleResult.Action.DROP &&
                        result != RuleResult.Action.REJECT)
                        log.error("Applying chain {} produced {}, not " +
                            "ACCEPT, DROP, or REJECT", chain.id, result)
                    result == RuleResult.Action.ACCEPT
                }

                thunk(egressPorts map {portchain => portchain._1.id})
        } recoverWith {
            case e =>
                log.error("Error getting chains for PortSet {}", portSetID)
                Promise.failed(e)(system.dispatcher)
        }
    }

    protected def translateToDpPorts(acts: Seq[FlowAction[_]], port: UUID,
                                   localPorts: Seq[Short],
                                   tunnelKey: Option[Long], tunnelPorts: Seq[Short],
                                   dpTags: mutable.Set[Any]): Seq[FlowAction[_]] = {
        tunnelKey match {
            case Some(k) =>
                log.debug("Translating output actions for vport (or set) {}," +
                    " having tunnel key {}, and corresponding to local dp " +
                    "ports {}, and tunnel ports {}",
                    port, k, localPorts, tunnelPorts)

            case None =>
                log.debug("No tunnel key provided. Translating output " +
                    "action for vport {}, corresponding to local dp port {}",
                    port, localPorts)
        }
        // TODO(pino): when we detect the flow won't have output actions,
        // set the flow to expire soon so that we can retry.
        if (localPorts.length == 0 && tunnelPorts.length == 0)
            log.error("No local datapath ports or tunnels found. This flow " +
                "will be dropped because we cannot make Output actions.")
        val newActs = ListBuffer[FlowAction[_]]()
        var newTags = new mutable.HashSet[Any]

        var translatablePort = port
        var translatedActions = localPorts.map { id =>
            FlowActions.output(id).asInstanceOf[FlowAction[_]]
        }
        // add tag for flow invalidation
        localPorts.foreach{id => newTags += FlowTagger.invalidateDPPort(id) }

        if (null != tunnelPorts && tunnelPorts.length > 0) {
            translatedActions = translatedActions ++ tunnelKey.map { key =>
                FlowActions.setKey(FlowKeys.tunnelID(key))
                    .asInstanceOf[FlowAction[_]]
            } ++ tunnelPorts.map { id =>
                FlowActions.output(id).asInstanceOf[FlowAction[_]]
            }
            tunnelPorts.foreach{id => newTags += FlowTagger.invalidateDPPort(id)}
        }

        for (act <- acts) {
            act match {
                case p: FlowActionOutputToVrnPort if (p.portId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                case p: FlowActionOutputToVrnPortSet if (p.portSetId == translatablePort) =>
                    newActs ++= translatedActions
                    translatablePort = null
                    if (dpTags != null)
                        dpTags ++= newTags

                // we only translate the first ones.
                case x: FlowActionOutputToVrnPort =>
                case x: FlowActionOutputToVrnPortSet =>

                case a => newActs += a
            }
        }
        newActs
    }

    protected def translateActions(actions: Seq[FlowAction[_]],
                                 inPortUUID: Option[UUID],
                                 dpTags: Option[mutable.Set[Any]],
                                 wMatch: WildcardMatch): Future[Seq[FlowAction[_]]] = {
        val translated = Promise[Seq[FlowAction[_]]]()

        // check for VRN port or portSet
        var vrnPort: Option[Either[UUID, UUID]] = None
        for (action <- actions) {
            action match {
                case s: FlowActionOutputToVrnPortSet if (vrnPort == None ) =>
                    vrnPort = Some(Right(s.portSetId))
                case p: FlowActionOutputToVrnPort if (vrnPort == None) =>
                    vrnPort = Some(Left(p.portId))
                case u: FlowActionUserspace =>
                    u.setUplinkPid(datapathConnection.getChannel.getLocalAddress.getPid)
                case _ =>
            }
        }

        vrnPort match {
            case Some(Right(portSet)) =>
                // we need to expand a port set

                val portSetFuture = ask(
                    VirtualToPhysicalMapper.getRef(),
                    PortSetRequest(portSet, update = false)).mapTo[PortSet]

                val bridgeFuture = VirtualTopologyActor.expiringAsk(
                        BridgeRequest(portSet, update = false)).mapTo[RCUBridge]

                portSetFuture map {
                    set => bridgeFuture onSuccess {
                        case br =>
                            // Don't include the input port in the expanded
                            // port set.
                            var outPorts = set.localPorts
                            inPortUUID foreach { p => outPorts -= p }
                            log.debug("Flooding on bridge {}. inPort: {}, " +
                                "local bridge ports: {}, " +
                                "remote hosts having ports on this bridge: {}",
                                br.id, inPortUUID, set.localPorts, set.hosts)
                            // add tag for flow invalidation
                            dpTags foreach { tags =>
                                tags += FlowTagger.invalidateBroadcastFlows(
                                    br.id, br.id)
                            }
                            val localPortFutures =
                                outPorts.toSeq map {
                                    portID =>
                                        VirtualTopologyActor.expiringAsk(
                                                PortRequest(portID, update = false))
                                            .mapTo[client.Port[_]]
                                }
                            Future.sequence(localPortFutures) onComplete {
                                case Right(localPorts) =>
                                    applyOutboundFilters(localPorts,
                                        portSet, wMatch,
                                        { portIDs => translated.success(
                                            translateToDpPorts(
                                                actions, portSet,
                                                portsForLocalPorts(portIDs),
                                                Some(br.tunnelKey),
                                                tunnelsForHosts(set.hosts.toSeq),
                                                dpTags.orNull))
                                        })

                                case _ => log.error("Error getting " +
                                    "configurations of local ports of " +
                                    "PortSet {}", portSet)
                            }
                    }
                }

            case Some(Left(port)) =>
                // we need to translate a single port
                dpState.vportResolver.getDpPortNumberForVport(port) match {
                    case Some(portNum) =>
                        translated.success(translateToDpPorts(
                            actions, port, List(portNum.shortValue()),
                            None, Nil, dpTags.orNull))
                    case None =>
                        VirtualTopologyActor.expiringAsk(
                            PortRequest(port, update = false))
                            .mapTo[client.Port[_]] map {
                                case p: ExteriorPort[_] =>
                                    translated.success(translateToDpPorts(
                                            actions, port, Nil,
                                            Some(p.tunnelKey),
                                            tunnelsForHosts(List(p.hostID)),
                                            dpTags.orNull))
                        }
                }
            case None =>
                translated.success(actions)
        }
        translated.future
    }

    protected def tunnelsForHosts(hosts: Seq[UUID]): Seq[Short] = {
        val tunnels = mutable.ListBuffer[Short]()

        def tunnelForHost(host: UUID): Option[Short] = {
            dpState.peerToTunnels.get(host).flatMap {
                mappings => mappings.values.headOption.map {
                    port => port.getPortNo.shortValue
                }
            }
        }

        for (host <- hosts)
            tunnels ++= tunnelForHost(host).toList

        tunnels
    }
}
