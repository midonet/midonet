// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import akka.util.Timeout
import scala.collection.JavaConversions._
import java.util.UUID

import org.apache.zookeeper.KeeperException
import org.slf4j.LoggerFactory

import com.midokura.midolman.packets.MAC
import com.midokura.midolman.state.{Directory, MacPortMap}
import com.midokura.midolman.util.Callback1


object BridgeStateOperation extends Enumeration {
    val PortOfMac = Value
    val CallForAllMacsOfPort = Value
    val IsKnownMac = Value
    val CallsDone = Value
    val UnknownMessageError = Value
}


class BridgeStateHelper(macPortDir: Directory) {
    import BridgeStateOperation._

    private val system = ActorSystem("BridgeStateHelper")  //XXX
    private val actor = system.actorOf(Props(new BridgeStateActor(macPortDir)),
                                       name="BridgeStateActor")
    final val shortTimeout = 20 milliseconds
    final val longTimeout = 2000 milliseconds

    private final val log = LoggerFactory.getLogger(this.getClass)

    //XXX: Watcher


    def portOfMac(mac: MAC): UUID = {
        val f = actor.ask((PortOfMac, mac))(shortTimeout) recover {
            case e => log.error("portOfMac: call failed", e)
        }
        Await.result(f.mapTo[UUID], shortTimeout)
        // TODO(jlm): How to specify the timeout once?
    }

    def callForAllMacsOfPort(portID: UUID, cb: Callback1[MAC]) {
        val f = actor.ask((CallForAllMacsOfPort, portID, 
                           cb))(longTimeout) recover {
            case e => log.error("callForAllMacsOfPort: call failed", e)
        }
        Await.result(f, longTimeout) match {
            case CallsDone => /* success */
            case x =>
                log.error("callForAllMacsOfPort: reply {} isn't CallsDone", x)
        }
    }

    def isKnownMac(mac: MAC): Boolean = {
        val f = actor.ask((PortOfMac, mac))(shortTimeout) recover {
            case e => log.error("isKnownMac: call failed", e)
        }
        Await.result(f.mapTo[Boolean], shortTimeout)
    }
}


class BridgeStateActor(macPortDir: Directory) extends Actor {
    import BridgeStateOperation._

    private final val macPortMap = new MacPortMap(macPortDir)
    private final val log = LoggerFactory.getLogger(this.getClass)

    def receive = {
                case (PortOfMac, mac: MAC) =>
                    reply(macPortMap.get(mac))
                case (CallForAllMacsOfPort, portID: UUID, cb: Callback1[MAC]) =>
                    for (mac <- macPortMap.getByValue(portID))
                        cb.call(mac)
                    reply(CallsDone)
                case (IsKnownMac, mac: MAC) =>
                    reply(macPortMap.containsKey(mac))
                case msg => 
                    log.error("got unknown message {}", msg)
                    reply(Status.Failure)
    }

    def reply(x: Any) = sender ! x
}
