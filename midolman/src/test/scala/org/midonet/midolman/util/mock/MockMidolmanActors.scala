/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

    protected def registerActors(actors: (Referenceable, () => Actor)*) =
        actorsService.register(actors)

    def toActorRef(ref: Referenceable): TestActorRef[Actor] =
        actorsService.actor(ref)

    implicit def toMessageAccumulator(ref: Referenceable): MessageAccumulator =
        toTypedActor(ref).as[MessageAccumulator]

    implicit def toTypedActor(ref: Referenceable) = new {
        def as[A] = toActorRef(ref).underlyingActor.asInstanceOf[A]
    }
}
