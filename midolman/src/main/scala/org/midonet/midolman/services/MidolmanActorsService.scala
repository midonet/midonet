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
package org.midonet.midolman.services

import java.util.concurrent.TimeoutException

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, gracefulStop}
import akka.util.Timeout

import com.google.common.util.concurrent.AbstractService
import com.google.inject.{Inject, Injector}

import org.slf4j.LoggerFactory

import org.midonet.midolman._
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.l4lb.HealthMonitor
import org.midonet.midolman.management.PacketTracing
import org.midonet.midolman.openstack.metadata.MetadataServiceManagerActor
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.routingprotocols.RoutingManagerActor.StopBgpHandlers
import org.midonet.midolman.services.MidolmanActorsService._

object MidolmanActorsService {

    final val ActorsStartTimeout = 60 seconds
    final val ActorsStopTimeout = 3 seconds
    final val ChildActorStartTimeout = 60 seconds
    final val ChildActorStopTimeout = 200 millis

}

/*
 * A base trait for a simple guice service that starts an actor system,
 * a SupervisorActor and set of top-level actors below the supervisor.
 *
 * Concrete classes need to override actorSpecs, to select which top-level
 * actors this service should provide.
 */
class MidolmanActorsService extends AbstractService {
    import SupervisorActor.StartChild

    private val log = LoggerFactory.getLogger(classOf[MidolmanActorsService])

    @Inject
    val injector: Injector = null
    @Inject
    val config: MidolmanConfig = null

    @Inject
    implicit val system: ActorSystem = null

    implicit def ex: ExecutionContext = system.dispatcher
    implicit protected val timeout = new Timeout(ChildActorStartTimeout)

    protected def actorSpecs = {
        val actors = ListBuffer(
            (propsFor(classOf[NetlinkCallbackDispatcher]),
                NetlinkCallbackDispatcher.Name),
            (propsFor(classOf[DatapathController]), DatapathController.Name),
            (propsFor(classOf[RoutingManagerActor]), RoutingManagerActor.Name))
        if (config.healthMonitor.enable)
            actors += (
                (propsFor(classOf[HealthMonitor])
                 .withDispatcher("actors.pinned-dispatcher"),
                 HealthMonitor.Name)
            )
        if (config.openstack.metadata.enabled)
            actors += (
                /*
                 * Use pinned-dispatcher to avoid interfering other actors.
                 * Note that MetadataServiceManagerActor performs blocking
                 * operations in its receive().
                 */
                (propsFor(classOf[MetadataServiceManagerActor]).
                     withDispatcher("actors.pinned-dispatcher"),
                 MetadataServiceManagerActor.Name)
            )
        actors.toList
    }

    protected var supervisorActor: ActorRef = _
    private var childrenActors: List[ActorRef] = Nil

    protected override def doStart() {
        try {
            log.info("Booting up actors service")

            PacketTracing.registerAsMXBean()
            supervisorActor = startTopActor(
                                propsFor(classOf[SupervisorActor]),
                                SupervisorActor.Name)

            childrenActors = actorSpecs map { s =>
                try {
                    Await.result(startActor(s), ChildActorStartTimeout)
                } catch {
                    case NonFatal(e) =>
                        // rethrow and propagate up to the injector
                        throw new Exception(s"${s._2} creation failed", e)
                }
            }

            childrenActors.awaitStart(ActorsStartTimeout)
            notifyStarted()
            log.info("Actors system started")
        } catch {
            case NonFatal(e) =>
                log.error("Error while starting midolman actors", e)
                notifyFailed(e)
        }
    }

    protected override def doStop() {
        log.info("Stopping BGP handler actors")
        val path = system / SupervisorActor.Name / RoutingManagerActor.Name
        Await.ready(system.actorSelection(path).resolveOne(),
                    ChildActorStopTimeout).onComplete {
            case Success(routingActor) =>
                Await.result(routingActor ? StopBgpHandlers(), 1 second)
            case Failure(_) =>
                log.warn(s"Unable to get ${RoutingManagerActor.Name} actor")
        }

        log.info("Stopping all actors")

        try {
            Await.result(stopActor(supervisorActor), ChildActorStopTimeout * actorSpecs.length)
            log.info("All actors stopped successfully")
        } catch {
            case NonFatal(e) =>
                log.error("Failed to gracefully stop all actors", e)
        }

        try {
            log.info("Stopping the actor system")
            system.shutdown()
            try { system.awaitTermination(ActorsStopTimeout) }
            catch {
                case e: TimeoutException =>
                    log.warn("Failed to gracefully stop the actor system")
            }
            system.dispatchers
                  .defaultGlobalDispatcher
                  .asInstanceOf[{def shutdown(): Unit}]
                  .shutdown()
            log.info("Actor system stopped")
            notifyStopped()
        } catch {
            case NonFatal(e) =>
                log.error("Failed to stop the actors system", e)
                notifyFailed(e)
        }
    }

    def propsFor[T <: Actor: ClassTag](actorClass: Class[T]) =
        Props { injector.getInstance(actorClass) }

    protected def stopActor(actorRef: ActorRef) = {
        log.info("Stopping actor: {}", actorRef.toString())
        gracefulStop(actorRef, ChildActorStopTimeout)
    }

    private def startTopActor(actorProps: Props, actorName: String) = {
        log.info("Starting actor {}", actorName)
        system.actorOf(actorProps, actorName)
    }

    protected def startActor(specs: (Props, String)): Future[ActorRef] = {
        val (props, name) = specs
        log.debug("Request for starting actor {}", name)
        (supervisorActor ? StartChild(props, name)).mapTo[ActorRef]
    }
}
