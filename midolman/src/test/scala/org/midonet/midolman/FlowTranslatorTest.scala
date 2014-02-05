/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import java.util.UUID

import scala.collection.immutable.List
import scala.collection.{Set => ROSet, mutable}
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import akka.actor.{Actor, ActorSystem}
import akka.event.{NoLogging, LoggingAdapter}
import akka.util.Timeout

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{OneInstancePerTest, GivenWhenThen, Matchers, FeatureSpec}

import org.midonet.cluster.data.Chain
import org.midonet.cluster.data.ports.BridgePort
import org.midonet.midolman.rules.{RuleResult, Condition}
import org.midonet.midolman.rules.RuleResult.Action
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.{FlowTagger, VirtualToPhysicalMapper, VirtualTopologyActor}
import org.midonet.midolman.topology.rcu.{PortSet, Host}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.PortSetRequest
import org.midonet.odp.DpPort
import org.midonet.odp.flows.FlowActions.{output, pushVLAN, setKey, userspace}
import org.midonet.odp.flows.{FlowKeys, FlowActions, FlowAction,
                              FlowActionOutput}
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.sdn.flows.VirtualActions.{FlowActionOutputToVrnPort,
                                             FlowActionOutputToVrnPortSet}
import org.midonet.util.concurrent.ExecutionContextOps

@RunWith(classOf[JUnitRunner])
class FlowTranslatorTest extends FeatureSpec
                         with Matchers
                         with GivenWhenThen
                         with MockMidolmanActors
                         with MidolmanServices
                         with VirtualConfigurationBuilders
                         with VirtualTopologyHelper
                         with OneInstancePerTest {

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
                                       with MessageAccumulator),
        VirtualToPhysicalMapper -> (() => new MyVirtualToPhysicalMapper
                                          with MessageAccumulator))

    class MyVirtualToPhysicalMapper extends Actor {
        val portSets = mutable.Map[UUID, PortSet]()

        def receive = {
            case PortSetRequest(portSetId, _) => sender ! portSets(portSetId)
        }
    }

    trait TranslationContext {
        protected val dpState: TestDatapathState

        var inPortUUID: Option[UUID] = None

        def uplinkPid(pid: Int): Unit =
            dpState.uplinkPid = pid

        def grePort(id: Int): Unit =
            dpState.grePort = id

        def local(binding: (UUID, Integer)): Unit =
            dpState.dpPortNumberForVport += binding

        def peer(binding: (UUID, (Int, Int))): Unit =
            dpState.peerTunnels += binding

        def input(id: UUID): Unit =
           inPortUUID = Some(id)

        def translate(action: FlowAction): Unit = translate(List(action))

        def translate(actions: List[FlowAction]): Unit
        def verify(result: (Seq[FlowAction], ROSet[Any]))

        protected def withInputPortTagging(tags: ROSet[Any]) = inPortUUID match {
            case Some(p) =>
                val inId = dpState.getDpPortNumberForVport(inPortUUID.get).get
                tags + FlowTagger.invalidateDPPort(inId.shortValue())
            case None => tags
        }
    }

    var id = 0

    def makePort(host: UUID)(f: BridgePort => BridgePort): BridgePort = {
        val bridge = newBridge("bridge" + id)
        id += 1
        val port = newBridgePort(bridge, f(new BridgePort().setHostId(host)))
        fetchTopology(port)
        port
    }

    def makePortSet(id: UUID, hosts: Set[UUID], localPorts: Set[UUID]): Unit = {
        VirtualToPhysicalMapper
                .as[MyVirtualToPhysicalMapper]
                .portSets.put(id, PortSet(id, hosts, localPorts))
    }

    def reject(port: BridgePort) = newChain(port, RuleResult.Action.REJECT)

    def accept(port: BridgePort) = newChain(port, RuleResult.Action.ACCEPT)

    def newChain(port: BridgePort, action: Action): Chain = {
        val chain = newOutboundChainOnPort("chain" + id, port)
        newLiteralRuleOnChain(chain, 1, new Condition(), action)
        fetchTopology(chain)
        chain
    }

    feature("FlowActionOutputToVrnPort is translated") {
        translationScenario("The port is local") { ctx =>
            val port = UUID.randomUUID()
            ctx local port -> 3

            ctx translate FlowActionOutputToVrnPort(port)
            ctx verify (List(output(3)),
                        Set(FlowTagger.invalidateDPPort(3)))
        }

        translationScenario("The port is remote") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost) { _
                .setInterfaceName("if")
            }
            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(port.getTunnelKey, 1, 2)),
                                   output(1342)),
                        Set(FlowTagger.invalidateTunnelPort((1, 2))))
        }

        translationScenario("The port is remote but interior") { ctx =>
            val remoteHost = UUID.randomUUID()
            val port = makePort(remoteHost)(identity) // makes isExterior be false

            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPort(port.getId)
            ctx verify (List.empty, Set.empty)
        }
    }

    feature("FlowActionOutputToVrnPortSet is translated") {
        translationScenario("The port set has local ports") { ctx =>
            val inPort = UUID.randomUUID()
            val port0 = makePort(hostId())(identity) // code assumes that they
            val port1 = makePort(hostId())(identity) // are exterior
            val chain1 = accept(port1)
            val port2 = makePort(hostId()) { p => p.setAdminStateUp(false)}
            val port3 = makePort(hostId())(identity)
            val chain3 = reject(port3)
            val bridge = newBridge("portSetBridge")
            makePortSet(bridge.getId, Set.empty, Set(inPort, port0.getId,
                                                     port1.getId, port2.getId,
                                                     port3.getId))
            ctx input inPort
            ctx local inPort -> 1
            ctx local port0.getId -> 2
            ctx local port1.getId -> 3
            ctx local port2.getId -> 4
            ctx local port3.getId -> 5

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(output(2), output(3)),
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(port0.getId),
                            FlowTagger.invalidateFlowsByDevice(port1.getId),
                            FlowTagger.invalidateFlowsByDevice(port2.getId),
                            FlowTagger.invalidateFlowsByDevice(port3.getId),
                            FlowTagger.invalidateFlowsByDevice(chain1.getId),
                            FlowTagger.invalidateFlowsByDevice(chain3.getId),
                            FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateDPPort(3)))
        }

        translationScenario("The port set has remote ports") { ctx =>
            val inPort = UUID.randomUUID()
            val remoteHost0 = UUID.randomUUID()
            val port0 = makePort(remoteHost0) { _
                .setInterfaceName("if")
            }
            val remoteHost1 = UUID.randomUUID()
            val port1 = makePort(remoteHost1) { _
                .setInterfaceName("if")
            }
            val bridge = newBridge("portSetBridge")
            makePortSet(bridge.getId, Set(port0.getHostId, port1.getHostId),
                        Set.empty)
            ctx input inPort
            ctx local inPort -> 9
            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx grePort 1342

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)
            ctx verify (List(setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateTunnelPort((1, 2)),
                            FlowTagger.invalidateTunnelPort((3, 4))))
        }

        translationScenario("Local and remote ports are translated") { ctx =>
            val remoteHost0 = UUID.randomUUID()
            val rport0 = makePort(remoteHost0) { _
                    .setInterfaceName("if")
            }
            val remoteHost1 = UUID.randomUUID()
            val rport1 = makePort(remoteHost1) { _
                    .setInterfaceName("if")
            }
            val lport0 = makePort(hostId())(identity) // code assumes that they
            val lport1 = makePort(hostId())(identity) // are exterior

            val bridge = newBridge("portSetBridge")
            makePortSet(bridge.getId, Set(rport0.getHostId, rport1.getHostId),
                        Set(lport0.getId, lport1.getId))

            ctx peer remoteHost0 -> (1, 2)
            ctx peer remoteHost1 -> (3, 4)
            ctx grePort 1342
            ctx local lport0.getId -> 2
            ctx local lport1.getId -> 3

            ctx translate FlowActionOutputToVrnPortSet(bridge.getId)

            ctx verify (List(output(2),
                             output(3),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 1, 2)),
                             output(1342),
                             setKey(FlowKeys.tunnel(bridge.getTunnelKey, 3, 4)),
                             output(1342)),
                        Set(FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(lport0.getId),
                            FlowTagger.invalidateFlowsByDevice(lport1.getId),
                            FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateDPPort(3),
                            FlowTagger.invalidateTunnelPort((1, 2)),
                            FlowTagger.invalidateTunnelPort((3, 4))))
        }
    }

    feature("UserspaceFlowAction is translated") {
        translationScenario("The uplink pid is correctly set") { ctx =>
            ctx uplinkPid 1

            ctx translate userspace()
            ctx verify (List(userspace(1)), Set.empty)
        }
    }

    feature("Other actions are identity-translated") {
        translationScenario("A pushVLAN action is identity-translated") { ctx =>
            ctx translate pushVLAN(3)
            ctx verify (List(pushVLAN(3)), Set.empty)
        }
    }

    feature("Multiple actions are translated") {
        translationScenario("Different types of actions are translated") { ctx =>
            val port0 = UUID.randomUUID()
            val port1 = makePort(hostId())(identity) // code assumes it's exterior
            val bridge = newBridge("portSetBridge")
            makePortSet(bridge.getId, Set.empty, Set(port1.getId))

            ctx local port0 -> 2
            ctx local port1.getId -> 3
            ctx uplinkPid 1

            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnPortSet(bridge.getId),
                               userspace(),
                               pushVLAN(3))
            ctx verify (List(output(2), output(3), userspace(1), pushVLAN(3)),
                        Set(FlowTagger.invalidateDPPort(2),
                            FlowTagger.invalidateBroadcastFlows(bridge.getId,
                                                                bridge.getId),
                            FlowTagger.invalidateFlowsByDevice(port1.getId),
                            FlowTagger.invalidateDPPort(3)))
        }

        translationScenario("Multiple actions of the same type are translated") { ctx =>
            val port0 = UUID.randomUUID()
            val remoteHost = UUID.randomUUID()
            val port1 = makePort(remoteHost) { _
                .setInterfaceName("if")
            }

            ctx local port0 -> 3
            ctx peer remoteHost -> (1, 2)
            ctx grePort 1342

            ctx translate List(FlowActionOutputToVrnPort(port0),
                               FlowActionOutputToVrnPort(port1.getId))
            ctx verify (List(output(3),
                             setKey(FlowKeys.tunnel(port1.getTunnelKey, 1, 2)),
                             output(1342)),
                        Set(FlowTagger.invalidateDPPort(3),
                            FlowTagger.invalidateTunnelPort((1, 2))))
        }
    }

    sealed class TestFlowTranslator(dps: DatapathState) extends FlowTranslator {
        implicit protected def system: ActorSystem = actorSystem
        implicit override protected def executor = ExecutionContext.callingThread
        implicit protected val requestReplyTimeout: Timeout = Timeout(3 seconds)
        val log: LoggingAdapter = NoLogging
        val cookieStr = ""
        val dpState = dps

        override def translateVirtualWildcardFlow(flow: WildcardFlow,
                                                  tags: ROSet[Any])
        : Future[(WildcardFlow, ROSet[Any])] =
            super.translateVirtualWildcardFlow(flow, tags)

        override def translateActions(actions: Seq[FlowAction],
                                      inPortUUID: Option[UUID],
                                      dpTags: Option[mutable.Set[Any]],
                                      wMatch: WildcardMatch)
        : Future[Seq[FlowAction]] =
            super.translateActions(actions, inPortUUID, dpTags, wMatch)
    }

    class TestDatapathState extends DatapathState {
        var version: Long = 0
        var host: Host = null
        var uplinkPid: Int = 0
        var dpPortNumberForVport = mutable.Map[UUID, Integer]()
        var peerTunnels = mutable.Map[UUID, (Int, Int)]()
        var grePort: Int = _

        def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
            dpPortNumberForVport get vportId

        def greOutputAction: Option[FlowActionOutput] =
            Some(FlowActions.output(grePort))

        def peerTunnelInfo(peer: UUID): Option[(Int, Int)] =
            peerTunnels get peer

        def tunnelGre: Option[DpPort] = None
        def tunnelVxLan: Option[DpPort] = None

        def vxLanOutputAction: Option[FlowActionOutput] = None
        def getDpPortForInterface(itfName: String): Option[DpPort] = None
        def getVportForDpPortNumber(portNum: Integer): Option[UUID] = None
        def getDpPortName(num: Integer): Option[String] = None
    }

    def translationScenario(name: String)
                           (testFun: TranslationContext => Unit): Unit = {
        translateWithAndWithoutTags(name, testFun)
        translateWildcardFlow(name, testFun)
    }

    def translateWithAndWithoutTags(name: String,
                                    testFun: TranslationContext => Unit): Unit = {
        for (withTags <- Array(false, true)) {
            val tags = mutable.Set[Any]()
            var translatedActions: Seq[FlowAction] = null

            val ctx = new TranslationContext() {
                protected val dpState = new TestDatapathState
                private val wcMatch = new WildcardMatch()

                def translate(actions: List[FlowAction]): Unit = {
                    if (inPortUUID.isDefined)
                        wcMatch.setInputPortUUID(inPortUUID.get)

                    val f = new TestFlowTranslator(dpState)
                            .translateActions(actions, inPortUUID,
                                if (withTags) Some(tags) else None,
                                wcMatch)
                    translatedActions = Await.result(f, 1 second)
                }

                def verify(result: (Seq[FlowAction], ROSet[Any])) = {
                    translatedActions should contain theSameElementsAs result._1
                    if (withTags)
                        tags should be (withInputPortTagging(result._2))
                    else
                        tags should be (Set.empty)
                }
            }

            scenario(name +
                    (if (withTags) " and proper tags are applied" else "")
            )(testFun(ctx))
        }
    }

    def translateWildcardFlow(name: String,
                              testFun: TranslationContext => Unit): Unit = {
        var translation: (WildcardFlow, ROSet[Any]) = null
        val ctx = new TranslationContext() {
            protected val dpState = new TestDatapathState
            private val wcMatch = new WildcardMatch()

            def translate(actions: List[FlowAction]): Unit = {
                if (!inPortUUID.isDefined) {
                    val in = UUID.randomUUID()
                    inPortUUID = Some(in)
                    local (in -> 99)
                }

                wcMatch.setInputPortUUID(inPortUUID.get)

                val f = new TestFlowTranslator(dpState)
                    .translateVirtualWildcardFlow(WildcardFlow(wcMatch, actions),
                                                  null)
                translation = Await.result(f, 1 second)
            }

            def verify(result: (Seq[FlowAction], ROSet[Any])) = {
                translation._1.actions should contain theSameElementsAs result._1
                translation._2 should be (withInputPortTagging(result._2))
            }
        }

        scenario(name + " via WildcardFlow")(testFun(ctx))
    }
}
