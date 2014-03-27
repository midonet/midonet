/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.util.mock

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import akka.actor.{ActorIdentity, Identify, Props, Actor}
import akka.pattern.ask
import akka.testkit.TestActorRef
import com.google.inject.Inject
import com.google.inject.Injector

import org.midonet.midolman.Referenceable
import org.midonet.midolman.services.MidolmanActorsService

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
        actors.get(actor.Name) getOrElse {
            throw new IllegalArgumentException(s"No actor named ${actor.Name}")
        }

    def register(actors: List[(Referenceable, () => MessageAccumulator)]) {
        props = (actors map {
            case (ref, f) => (ref.Name, Props(injectedActor(f)))
        }).toMap
    }

    private def injectedActor(f: => () => Actor) = {
        val instance = f()
        injector.injectMembers(instance)
        instance
    }

    override protected def startActor(actorProps: Props, name: String) = {
        val p = props.getOrElse(name, Props(new EmptyActor
                                            with MessageAccumulator))
        // Because actors are started asynchronously, creating a TestActorRef
        // may fail because the supervisorActor may not have started yet. See
        // TestActorRef.scala#L35 for details (version 2.2.3).
        val supervisor = Await.result(supervisorActor ? Identify(null),
                                      Duration.Inf)
                              .asInstanceOf[ActorIdentity].ref.get
        val testRef = TestActorRef[MessageAccumulator](p, supervisor, name)
        actors += (name -> testRef)
        testRef
    }

    override def initProcessing() { }
}