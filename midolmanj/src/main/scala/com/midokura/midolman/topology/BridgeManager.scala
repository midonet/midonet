/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.topology

import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import scala.collection.mutable
import java.util.UUID

import com.midokura.midolman.simulation.Bridge
import com.midokura.midolman.state.zkManagers.BridgeZkManager
import com.midokura.midolman.state.zkManagers.BridgeZkManager.BridgeConfig
import com.midokura.midonet.cluster.client.MacLearningTable
import com.midokura.packets.MAC


/* The MacFlowCount is called from the Coordinators' actors and dispatches
 * to the BridgeManager's actor to get/modify the flow counts.  */
trait MacFlowCount {
    def getCount(mac: MAC, port: UUID): Int
    def increment(mac: MAC, port: UUID): Unit
    def decrement(mac: MAC, port: UUID): Unit
}


class BridgeManager(id: UUID, val mgr: BridgeZkManager)
        extends DeviceManager(id) {
    private var cfg: BridgeConfig = null

    private val macPortMap: MacLearningTable = null     //XXX
    private val flowCounts = new MacFlowCountImpl
    private val flowCountMap = new mutable.HashMap[(MAC, UUID), Int]()

    override def chainsUpdated() = {
        log.info("chains updated")
        context.actorFor("..").tell(
                new Bridge(id, cfg, macPortMap, flowCounts,
                           inFilter, outFilter))
    }

    override def refreshConfig() = {
        log.info("refresh config")
        cfg = mgr.get(id, cb)
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
                case Some(int: Int) => flowCountMap.put((mac, port), int+1)
                case None => flowCountMap.put((mac, port), 1)
            }

        case FlowDecrement(mac, port) =>
            flowCountMap.get((mac, port)) match {
                case Some(1) => flowCountMap.remove((mac, port))
                case Some(int: Int) => flowCountMap.put((mac, port), int-1)
                case None => 
                        log.error("Decrement of nonexistant flow count {} {}",
                                  mac, port)
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
}
