// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import scala.actors.Actor
import scala.collection.JavaConversions._
import java.lang.Iterable  // shadow Scala's Iterable
import java.util.UUID
import org.apache.zookeeper.KeeperException
import com.midokura.midolman.actors.ChanneledActor._
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

    //XXX: Watcher


    def portOfMac(mac: MAC) = {
        (PortOfMac, mac, Actor.self) !: actor
        Actor.self.receive { case x: UUID => x }
    }

    def callForAllMacsOfPort(portID: UUID, cb: Callback1[MAC]) {
        actor ! ((CallForAllMacsOfPort, portID, cb, Actor.self))
        Actor.self.receive { case CallsDone => }
    }

    def isKnownMac(mac: MAC) = {
        actor ! ((IsKnownMac, mac, Actor.self))
        Actor.self.receive { case x: Boolean => x }
    }

}


class BridgeStateActor(macPortDir: Directory) extends Actor {
    import BridgeStateOperation._

    private final val macPortMap = new MacPortMap(macPortDir)

    def act() {
        loop {
            react {
                case (PortOfMac, mac: MAC, actor: Actor) =>
                    macPortMap.get(mac) !: actor
                case (CallForAllMacsOfPort, portID: UUID, cb: Callback1[MAC],
                      actor: Actor) =>
                    for (mac <- macPortMap.getByValue(portID))
                        cb.call(mac)
                    CallsDone !: actor
                case (IsKnownMac, mac: MAC, actor: Actor) =>
                    macPortMap.containsKey(mac) !: actor
                case msg => println("got unknown message " + msg)
            }
        }
    }
}
