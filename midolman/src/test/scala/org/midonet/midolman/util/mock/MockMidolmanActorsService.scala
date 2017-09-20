/*
 * Copyright 2016 Midokura SARL
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

import java.util.concurrent.CountDownLatch

import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorIdentity, Identify, Props}
import akka.pattern.ask
import akka.testkit.TestActorRef

import com.google.inject.{Inject, Injector}

import org.midonet.midolman.Referenceable
import org.midonet.midolman.services.MidolmanActorsService

class EmptyActor extends Actor {
    def receive: PartialFunction[Any, Unit] = Actor.emptyBehavior
}

class AwaitableActor(count: Int = 1) extends Actor {
    private var counter = new CountDownLatch(count)

    override def receive: PartialFunction[Any, Unit] = {
        case msg =>
            counter.countDown()
    }

    def await(timeout: Duration, count: Int = 1): Boolean = {
        val result = counter.await(timeout.length, timeout.unit)
        counter = new CountDownLatch(count)
        result
    }
}

trait MessageAccumulator extends Actor {
    var messages = List[Any]()

    def getAndClear() = {
        val ret = messages
        messages = List[Any]()
        ret
    }

    abstract override def receive = {
        case msg =>
            messages = messages :+ msg
            if (super.receive.isDefinedAt(msg))
                super.receive.apply(msg)
    }
}

/**
 * An actors service where all well-known actors are MessageAccumulator instances
 */
sealed class MockMidolmanActorsService extends MidolmanActorsService {

    @Inject
    override val injector: Injector = null
    private[this] var props = mutable.Map[String, Props]()
    private[this] val actors = mutable.Map[String, TestActorRef[Actor]]()
    var dispatcher: String = _

    def actor(actor: Referenceable): TestActorRef[Actor] =
        actors.getOrElse(
            actor.Name,
            throw new IllegalArgumentException(s"No actor named ${actor.Name}"))

    def register(actors: Seq[(Referenceable, () => Actor)]): Unit = {
        actors foreach { case (ref, f) =>
            props += ref.Name -> Props(injectedActor(f))
        }
    }

    private def injectedActor(f: => () => Actor) = {
        val instance = f()
        injector.injectMembers(instance)
        instance
    }

    def setDispatcher(props: Props): Props =
        if ((dispatcher ne null) && dispatcher != "")
            props.withDispatcher(dispatcher)
        else
            props

    override protected def startActor(specs: (Props, String)) = {
        val (_, name) = specs
        var p = props.getOrElse(name, Props(new EmptyActor with MessageAccumulator))

        p = setDispatcher(p)

        // Because actors are started asynchronously, creating a TestActorRef
        // may fail because the supervisorActor may not have started yet. See
        // TestActorRef.scala#L35 for details (version 2.2.3).
        val supervisor = Await.result(supervisorActor ? Identify(null),
                                      Duration.Inf)
                              .asInstanceOf[ActorIdentity].ref.get
        val testRef = TestActorRef[Actor](p, supervisor, name)
        actors += (name -> testRef)
        Future successful testRef
    }

    override protected def stopRoutingHandlerActors() = {}
}
