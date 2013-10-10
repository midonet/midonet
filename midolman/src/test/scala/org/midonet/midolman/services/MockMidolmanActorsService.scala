/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.services

import akka.actor.{Props, Actor}
import akka.testkit.TestActorRef
import com.google.inject.Inject
import com.google.inject.Injector

import org.midonet.midolman.DatapathController
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.FlowController
import org.midonet.midolman.NetlinkCallbackDispatcher
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor

class MessageAccumulator extends Actor {
    private var msgs = List[Any]()

    override def receive = {
        case msg => msgs ::= msg
    }

    def get = msgs

    def getAndClear = {
        val ret = msgs
        msgs = Nil
        ret
    }
}

/**
 * An actors service where all well-known actors are MessageAccumulator instances
 */
class MockMidolmanActorsService extends MidolmanActorsService {
    @Inject
    override val injector: Injector = null

    implicit def actorSystem = system

    private var actorRefs = Map[String, TestActorRef[MessageAccumulator]]()

    def actor(name: String) =
        actorRefs.get(name) map { case ref => ref.underlyingActor }

    private def mockProps = new Props(() => new MessageAccumulator())

    override protected def actorSpecs = List(
        (mockProps, VirtualTopologyActor.Name),
        (mockProps, VirtualToPhysicalMapper.Name),
        (mockProps, DatapathController.Name),
        (mockProps, FlowController.Name),
        (mockProps, RoutingManagerActor.Name),
        (mockProps, DeduplicationActor.Name),
        (mockProps, NetlinkCallbackDispatcher.Name))

    override protected def startActor(actorProps: Props, actorName: String) = {
        supervisorActor map {
            supervisor =>
                val testRef = TestActorRef[MessageAccumulator](actorProps, supervisor, actorName)
                actorRefs += (actorName -> testRef)
                testRef
        } getOrElse {
            throw new IllegalArgumentException("No supervisor actor")
        }
    }

    override def initProcessing() {}

}
