// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._
import scala.collection.JavaConversions._
import java.util.UUID

// TODO(jlm): Use the Akka logger.
import org.slf4j.LoggerFactory

import com.midokura.midolman.packets.MAC
import com.midokura.midolman.state.{Directory, MacPortMap}
import com.midokura.midolman.util.Callback1


abstract class BridgeStateRequest
case class PortOfMac(mac: MAC) extends BridgeStateRequest
case class CallForAllMacsOfPort(portID: UUID, cb: Callback1[MAC]) 
        extends BridgeStateRequest
case class IsKnownMac(mac: MAC) extends BridgeStateRequest


class BridgeStateHelper(macPortDir: Directory) {
    private val system = ActorSystem("BridgeStateHelper")  //XXX
    private val actor = system.actorOf(Props(new BridgeStateActor(macPortDir)),
                                       name="BridgeStateActor")
    final val shortTimeout = 20 milliseconds
    final val longTimeout = 2000 milliseconds

    private final val log = LoggerFactory.getLogger(this.getClass)

    //XXX: Watcher


    def portOfMac(mac: MAC): UUID = {
        val f = actor.ask(PortOfMac(mac))(shortTimeout) recover {
            case e => log.error("portOfMac: call failed", e)
        }
        Await.result(f.mapTo[UUID], shortTimeout)
        // TODO(jlm): How to specify the timeout once?
    }

    def callForAllMacsOfPort(portID: UUID, cb: Callback1[MAC]) {
        val f = actor.ask(
                CallForAllMacsOfPort(portID, cb))(longTimeout) recover {
            case e => log.error("callForAllMacsOfPort: call failed", e)
        }
        Await.result(f, longTimeout) match {
            case () => /* success */
            case x =>
                log.error("callForAllMacsOfPort: reply {} isn't ()", x)
        }
    }

    def isKnownMac(mac: MAC): Boolean = {
        val f = actor.ask(PortOfMac(mac))(shortTimeout) recover {
            case e => log.error("isKnownMac: call failed", e)
        }
        Await.result(f.mapTo[Boolean], shortTimeout)
    }
}


class BridgeStateActor(macPortDir: Directory) extends Actor {
    private final val macPortMap = new MacPortMap(macPortDir)
    private final val log = LoggerFactory.getLogger(this.getClass)

    def receive = new Receive {
        def apply(msg: Any) { try {
            msg match {
                case PortOfMac(mac) =>
                    reply(macPortMap.get(mac))
                case CallForAllMacsOfPort(portID, cb) =>
                    for (mac <- macPortMap.getByValue(portID))
                        cb.call(mac)
                    reply(())
                case IsKnownMac(mac) =>
                    reply(macPortMap.containsKey(mac))
            }
        } catch {
            case e: Exception =>
                reply(Status.Failure(e))
        }}

        def isDefinedAt(msg: Any) = msg.isInstanceOf[BridgeStateRequest]
    }

    def reply(x: Any) = sender ! x
}
