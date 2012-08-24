/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._

import com.midokura.midonet.cluster.Client
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midolman.simulation.Bridge
import com.midokura.midonet.cluster.client._
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.sdn.flows.WildcardMatch
import com.midokura.midolman.FlowController
import akka.actor.{ActorRef, Actor}
import com.midokura.packets.{IntIPv4, MAC}
import java.util.{Map, UUID}
import com.midokura.util.functors.{Callback0, Callback1, Callback3}
import collection.{JavaConversions, mutable}


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def getCount(mac: MAC, port: UUID): Int

    def increment(mac: MAC, port: UUID): Unit

    def decrement(mac: MAC, port: UUID): Unit
}

trait RemoveFlowCallbackGenerator {
    def getCallback(mac: MAC,  port: UUID) : Callback0
}


class BridgeManager(id: UUID, val clusterClient: Client)
    extends DeviceManager(id) {
    private var cfg: BridgeConfig = new BridgeConfig()

    private var macPortMap: MacLearningTable = null
    private val flowCounts = new MacFlowCountImpl
    private val flowCountMap = new mutable.HashMap[(MAC, UUID), Int]()
    private val flowRemovedCallback = new RemoveFlowCallbackGeneratorImpl

    private var rtrMacToLogicalPortId : mutable.Map[MAC, UUID] = null
    private var rtrIpToMac : mutable.Map[IntIPv4, MAC] = null

    def flowController(): ActorRef = {
        context.actorFor("/user/%s" format FlowController.Name)
    }

    override def chainsUpdated() = {
        log.info("chains updated")
        context.actorFor("..").tell(
            new Bridge(id, cfg, macPortMap, flowCounts,
                inFilter, outFilter, flowRemovedCallback, rtrMacToLogicalPortId,
                rtrIpToMac))
    }

    override def updateConfig() = {
        log.info("refresh config")
        clusterClient.getBridge(id, new BridgeBuilderImpl)
    }

    override def getInFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.inboundFilter
        }
    }

    override def getOutFilterID() = {
        cfg match {
            case null => null;
            case _ => cfg.outboundFilter
        }
    }
    
    private case class FlowIncrement(mac: MAC, port: UUID)

    private case class FlowDecrement(mac: MAC, port: UUID)

    private case class GetFlowCount(mac: MAC, port: UUID)

    override def receive() = super.receive orElse {
        case SetBridgePortLocal(_, portId, local) => // TODO XXX

        case GetFlowCount(mac, port) =>
            sender ! (flowCountMap.get((mac, port)) match {
                case Some(int) => int
                case None => 0
            })

        case FlowIncrement(mac, port) =>
            flowCountMap.get((mac, port)) match {
                case Some(int: Int) => flowCountMap.put((mac, port), int + 1)
                case None =>
                    flowCountMap.put((mac, port), 1)
                //XXX: Remove any delayed deletes for this MAC/port
            }

        case FlowDecrement(mac, port) =>
            flowCountMap.get((mac, port)) match {
                case Some(1) => {
                    flowCountMap.remove((mac, port))
                    macPortMap.remove(mac, port)
                }
                case Some(int: Int) => flowCountMap.put((mac, port), int - 1)
                case None =>
                    log.error("Decrement of nonexistant flow count {} {}",
                        mac, port)
            }
    }

    private class MacTableNotifyCallBack extends Callback3[MAC, UUID, UUID] {
        def call(mac: MAC, oldPort: UUID, newPort: UUID) {

            //1. MAC was deleted
            //2. the MAC moved from port-x to port-y
            //3. MAC was added (delete the flow for the flood, oldPort = null)
            flowController() ! FlowController.InvalidateFlowByTag((id, mac, oldPort))
        }
    }

    private class MacFlowCountImpl extends MacFlowCount {
        def increment(mac: MAC, port: UUID) {
            self ! FlowIncrement(mac, port)
        }

        def decrement(mac: MAC, port: UUID) {
            self ! FlowDecrement(mac, port)
        }

        def getCount(mac: MAC, port: UUID): Int = {
            implicit val timeout = Timeout(1 millisecond)
            Await.result(self ? GetFlowCount(mac, port),
                timeout.duration).asInstanceOf[Int]
        }
    }

    class BridgeBuilderImpl extends BridgeBuilder
            with DeviceBuilderImpl[ForwardingElementBuilder] {

        def setTunnelKey(key: Long) {
            cfg.greKey = key.toInt
        }

        def setMacLearningTable(table: MacLearningTable) {
            // check if we should overwrite it
            if (table != null) {
                macPortMap = table
                macPortMap.notify(new MacTableNotifyCallBack)
            }
        }

        def setSourceNatResource(resource: SourceNatResource) {}

        def setID(id: UUID) = null //useless TODO(ross): delete it

        def setInFilter(filterID: UUID) = {
            cfg.inboundFilter = filterID
            this
        }

        def setOutFilter(filterID: UUID) = {
            cfg.outboundFilter = filterID
            this
        }

        def start() = null

        def setLogicalPortsMap(newRtrMacToLogicalPortId: Map[MAC, UUID],
                               newRtrIpToMac: Map[IntIPv4, MAC]) {
            import JavaConversions._
            rtrMacToLogicalPortId = newRtrMacToLogicalPortId
            rtrIpToMac = newRtrIpToMac
        }
    }

    class RemoveFlowCallbackGeneratorImpl() extends RemoveFlowCallbackGenerator{
        def getCallback(mac: MAC, port: UUID): Callback0 = {
            new Callback0() {
                def call() {
                    // TODO(ross): check, is this the proper self, that is BridgeManager?
                    // or it will be the self of the actor who execute this callback?
                    self ! FlowDecrement(mac, port)
                }
            }
        }
    }
}
