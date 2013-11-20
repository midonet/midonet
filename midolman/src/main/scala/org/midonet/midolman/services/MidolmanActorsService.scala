/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.services

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.{gracefulStop, ask}
import akka.util.Timeout

import com.google.common.util.concurrent.AbstractService
import com.google.inject.{Inject, Injector}
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

import org.midonet.midolman.SupervisorActor
import org.midonet.midolman.SupervisorActor.StartChild
import org.midonet.midolman.DatapathController
import org.midonet.midolman.DeduplicationActor
import org.midonet.midolman.FlowController
import org.midonet.midolman.NetlinkCallbackDispatcher
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.topology.VirtualToPhysicalMapper
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.monitoring.MonitoringActor

/*
 * A base trait for a simple guice service that starts an actor system,
 * a SupervisorActor and set of top-level actors below the supervisor.
 *
 * Concrete classes need to override actorSpecs, to select which top-level
 * actors this service should provide.
 */
class MidolmanActorsService extends AbstractService {
    private val log = LoggerFactory.getLogger(classOf[MidolmanActorsService])

    @Inject
    val injector: Injector = null
    @Inject
    val config: MidolmanConfig = null

    private var _system: ActorSystem = null
    implicit def system: ActorSystem = _system
    implicit protected val tout = new Timeout(3 seconds)

    protected def actorSpecs = {
        val specs = List(
            (propsFor(classOf[VirtualTopologyActor]),      VirtualTopologyActor.Name),
            (propsFor(classOf[VirtualToPhysicalMapper]).
                withDispatcher("actors.stash-dispatcher"), VirtualToPhysicalMapper.Name),
            (propsFor(classOf[DatapathController]),        DatapathController.Name),
            (propsFor(classOf[FlowController]),            FlowController.Name),
            (propsFor(classOf[RoutingManagerActor]),       RoutingManagerActor.Name),
            (propsFor(classOf[DeduplicationActor]),        DeduplicationActor.Name),
            (propsFor(classOf[NetlinkCallbackDispatcher]), NetlinkCallbackDispatcher.Name))

        if (config.getMidolmanEnableMonitoring)
            (propsFor(classOf[MonitoringActor]), MonitoringActor.Name) :: specs
        else
            specs
    }

    protected var supervisorActor: ActorRef = _
    private var childrenActors: List[ActorRef] = Nil

    protected override def doStart() {
        try {
            log.info("Booting up actors service")

            log.debug("Creating actors system.")
            _system = ActorSystem.create("MidolmanActors",
                ConfigFactory.load().getConfig("midolman"))

            supervisorActor = startTopActor(
                                propsFor(classOf[SupervisorActor]),
                                SupervisorActor.Name)

            childrenActors = for ((props, name) <- actorSpecs)
                yield startActor(props, name)

            notifyStarted()
            log.info("Actors system started")
        } catch {
            case e: Throwable =>
                log.error("Exception", e)
                notifyFailed(e)
        }
    }

    protected override def doStop() {
        try {
            val stopFutures = childrenActors map { child => stopActor(child) }
            Await.result(Future.sequence(stopFutures), 150 millis)
            stopActor(supervisorActor)
            log.debug("Stopping the actor system")
            system.shutdown()
            notifyStopped()
        } catch {
            case e: Throwable =>
                log.error("Exception", e)
                notifyFailed(e)
        }
    }

    def propsFor[T <: Actor: ClassTag](actorClass: Class[T]) =
        Props { injector.getInstance(actorClass) }

    protected def stopActor(actorRef: ActorRef) = {
        log.debug("Stopping actor: {}", actorRef.toString())
        gracefulStop(actorRef, 100.millis)
    }

    private def startTopActor(actorProps: Props, actorName: String) = {
        log.debug("Starting actor {}", actorName)
        system.actorOf(actorProps, actorName)
    }

    protected def startActor(actorProps: Props, actorName: String): ActorRef = {
        log.debug("Starting actor {}", actorName)
        Await.result(ask(supervisorActor, new StartChild(actorProps, actorName)),
                     tout.duration).asInstanceOf[ActorRef]
    }

    def initProcessing() {
        log.debug("Sending Initialization message to datapath controller.")
        DatapathController.getRef(system) ! DatapathController.initializeMsg
    }
}
