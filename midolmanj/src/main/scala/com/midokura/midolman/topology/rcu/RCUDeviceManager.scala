/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.topology.rcu

import akka.actor.{ActorLogging, ActorRef, Actor}
import com.midokura.midonet.cluster.Client
import com.midokura.midolman.topology.rcu.RCUDeviceManager.Start
import java.util.UUID
import com.midokura.midolman.logging.ActorLogWithoutPath

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