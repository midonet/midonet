// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import scala.actors.Actor
import scala.collection.JavaConversions._
import java.lang.Iterable  // shadow Scala's Iterable
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
}


class BridgeStateHelper(macPortDir: Directory) {
    import BridgeStateOperation._

    private val actor = new BridgeStateActor(macPortDir)
    final val shortTimeout = 20    // milliseconds
    final val longTimeout = 2000   // milliseconds

    private final val log = LoggerFactory.getLogger(this.getClass)

    //XXX: Watcher


    def portOfMac(mac: MAC): UUID = {
        actor !? (shortTimeout, (PortOfMac, mac)) match {
            case Some(x: UUID) => return x
            case None =>  /* timeout */
                log.warn("portOfMac: timeout {} exceeded", shortTimeout)
                return null
            case Some(x) =>  /* type error */
                log.error("portOfMac: reply {} isn't UUID", x)
                return null
        }
    }

    def callForAllMacsOfPort(portID: UUID, cb: Callback1[MAC]) {
        actor !? (longTimeout, (CallForAllMacsOfPort, portID, cb)) match {
            case Some(CallsDone) => /* normal */
            case None => /* timeout */
                log.warn("callForAllMacsOfPort: timeout {} exceeded",
                            longTimeout)
            case Some(x) => /* type error */
                log.error("callForAllMacsOfPort: reply {} isn't CallsDone", x)
        }
    }

    def isKnownMac(mac: MAC): Boolean = {
        actor !? (shortTimeout, (IsKnownMac, mac)) match {
            case Some(x: Boolean) => return x
            case None =>  /* timeout */
                log.warn("isKnownMac: timeout {} exceeded", shortTimeout)
                return false
            case Some(x) =>  /* type error */
                log.error("isKnownMac: reply {} isn't Boolean", x)
                return false
        }
    }

}


class BridgeStateActor(macPortDir: Directory) extends Actor {
    import BridgeStateOperation._

    private final val macPortMap = new MacPortMap(macPortDir)
    private final val log = LoggerFactory.getLogger(this.getClass)

    def act() {
        loop {
            react {
                case (PortOfMac, mac: MAC) =>
                    reply(macPortMap.get(mac))
                case (CallForAllMacsOfPort, portID: UUID, cb: Callback1[MAC]) =>
                    for (mac <- macPortMap.getByValue(portID))
                        cb.call(mac)
                    reply(CallsDone)
                case (IsKnownMac, mac: MAC) =>
                    reply(macPortMap.containsKey(mac))
                case msg => log.error("got unknown message " + msg)
            }
        }
    }
}
