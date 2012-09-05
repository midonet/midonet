/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology.rcu

import akka.actor.{ActorRef, Actor}
import akka.event.Logging
import javax.inject.Inject
import com.midokura.midonet.cluster.Client
import com.midokura.midolman.topology.rcu.RCUDeviceManager.Start
import java.util.UUID

trait RCUDeviceManager extends Actor {

     val log = Logging(context.system, this)

     def clusterClient: Client

     protected def receive = {
         case Start(deviceId) =>
             startManager(deviceId, context.actorFor(".."))

         case m =>
             log.info("Can't process message: {}", m)

     }

     protected def startManager(deviceId: UUID, clientActor: ActorRef)
 }

object RCUDeviceManager {
    case class Start(deviceId: UUID)
}