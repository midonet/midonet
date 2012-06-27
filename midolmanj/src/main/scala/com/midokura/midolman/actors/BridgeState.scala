// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import scala.actors.Actor
import java.lang.Iterable  // shadow Scala's Iterable
import java.util.UUID
import org.apache.zookeeper.KeeperException
//import com.midokura.midolman.actors.ChanneledActor
import com.midokura.midolman.packets.MAC
import com.midokura.midolman.state.{Directory, MacPortMap}


object BridgeStateOperation extends Enumeration {
    val PortOfMac = Value
    val MacsOfPort = Value
    val IsKnownMac = Value
}


class BridgeStateHelper(macPortDir: Directory) {
    import BridgeStateOperation._

    private val actor = new BridgeStateActor(macPortDir)

    //XXX: Watcher


    def portOfMac(mac: MAC) = {
        (PortOfMac, mac, Actor.self) !: actor
        Actor.self.receive { case x: UUID => x }
    }

    def macsOfPort(portID: UUID): Iterable[MAC] = {
        actor ! ((MacsOfPort, portID, Actor.self))
        Actor.self.receive { case x: Iterable[MAC] => x }
    }

    def isKnownMac(mac: MAC) = {
        actor ! ((IsKnownMac, mac, Actor.self))
        Actor.self.receive { case x: Boolean => x }
    }

}


class BridgeStateActor(macPortDir: Directory)
        extends Actor {
    import BridgeStateOperation._

    private final val macPortMap = new MacPortMap(macPortDir)

    def act() {
        loop {
            react {
                case (PortOfMac, mac: MAC, actor: Actor) =>
                     // macPortMap.get(mac) !: actor
                     actor ! macPortMap.get(mac)
                case (MacsOfPort, portID: UUID, actor: Actor) =>
                    actor ! macPortMap.getByValue(portID)
                case (IsKnownMac, mac: MAC, actor: Actor) =>
                    actor ! macPortMap.containsKey(mac)
                case msg => println("got unknown message " + msg)
            }
        }
    }

    def !: (msg: Any) = this ! msg
}
