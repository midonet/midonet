/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.util.mock

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration

import akka.actor.{Actor, ActorIdentity, ActorSystem, Identify, Props}
import akka.pattern.ask
import akka.testkit.TestActorRef
import com.typesafe.config.{ConfigValueFactory, ConfigFactory}

import com.google.inject.{Inject, Injector}

import org.midonet.midolman.{MockScheduler, Referenceable}
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

    override protected def startActor(specs: (Props, String)) = {
        val (_, name) = specs
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
        Future successful testRef
    }

    override def createActorSystem(): ActorSystem =
        ActorSystem.create("MidolmanActors", ConfigFactory.load()
            .getConfig("midolman")
            .withValue("akka.scheduler.implementation",
                       ConfigValueFactory.fromAnyRef(classOf[MockScheduler].getName)))

    override def initProcessing() { }
}
