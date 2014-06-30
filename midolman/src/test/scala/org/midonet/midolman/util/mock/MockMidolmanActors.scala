/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.util.mock

import scala.concurrent.ExecutionContext

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef

import org.midonet.midolman.{MockScheduler, Referenceable}
import org.midonet.util.concurrent._

trait MockMidolmanActors {

    protected[this] val actorsService = new MockMidolmanActorsService
    implicit def actorSystem: ActorSystem = actorsService.system
    implicit var executionContext: ExecutionContext = ExecutionContext.callingThread

    def scheduler = actorSystem.scheduler.asInstanceOf[MockScheduler]

    protected def withDefaultDispatcher() =
        withDispatcher("akka.actor.default-dispatcher")

    protected def withDispatcher(dispatcher: String) =
        actorsService.dispatcher = dispatcher

    protected def registerActors(actors: (Referenceable, () => MessageAccumulator)*) =
        actorsService.register(actors)

    implicit def toActorRef(ref: Referenceable): TestActorRef[MessageAccumulator] =
        actorsService.actor(ref)

    implicit def toMessageAccumulator(ref: Referenceable): MessageAccumulator =
        toActorRef(ref).underlyingActor

    implicit def toTypedActor(ref: Referenceable) = new {
        def as[A] = toMessageAccumulator(ref).asInstanceOf[A with MessageAccumulator]
    }
}
