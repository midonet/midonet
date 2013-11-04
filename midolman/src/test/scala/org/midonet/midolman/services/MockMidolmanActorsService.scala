/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.services

import com.google.inject.Inject
import com.google.inject.Injector

import scala.collection.immutable.Queue
import scala.collection.mutable

import akka.actor.{Props, Actor}
import akka.testkit.TestActorRef

import org.midonet.midolman._

class EmptyActor extends Actor {
    def receive: PartialFunction[Any, Unit] = Actor.emptyBehavior
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
    private[this] var props: Map[String, Props] = null
    private[this] val actors =
        mutable.Map[String, TestActorRef[MessageAccumulator]]()

    def actor(actor: Referenceable): TestActorRef[MessageAccumulator] =
        actors.get(actor.Name).get

    def register(actors: List[(Referenceable, () => MessageAccumulator)]) {
        props = (actors map {
            case (ref, f) => (ref.Name,Props(() => injectedActor(f)))
        }).toMap
    }

    private def injectedActor(f: => () => Actor) = {
        val instance = f()
        injector.injectMembers(instance)
        instance
    }

    override protected def startActor(actorProps: Props, name: String) = {
        supervisorActor map {
            supervisor =>
                val p = props.getOrElse(name, Props(() => new EmptyActor
                                                       with MessageAccumulator))
                val testRef = TestActorRef[MessageAccumulator](p, supervisor,
                                                               name)(system)
                actors += (name -> testRef)
                testRef
        } getOrElse {
            throw new IllegalArgumentException("No supervisor actor")
        }
    }

    override def initProcessing() { }
}