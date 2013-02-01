/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.topology.rcu

import akka.actor.{ActorLogging, ActorRef, Actor}
import org.midonet.cluster.Client
import org.midonet.midolman.topology.rcu.RCUDeviceManager.Start
import java.util.UUID
import org.midonet.midolman.logging.ActorLogWithoutPath

trait RCUDeviceManager extends Actor with ActorLogWithoutPath {

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
