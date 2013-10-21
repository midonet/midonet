/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman.services

import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.UntypedActorFactory
import akka.dispatch.{Await, Future}
import akka.pattern.Patterns
import akka.util.Duration
import akka.util.duration._
import akka.util.Timeout
import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import com.google.inject.Injector
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

class GuiceActorFactory
        (val injector: Injector, actorClass: Class[_ <: Actor]) extends UntypedActorFactory {

    override def create(): Actor = injector.getInstance(actorClass)
}

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
    def system: ActorSystem = _system

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

    protected var supervisorActor: Option[ActorRef] = None
    private var childrenActors: List[ActorRef] = Nil


    protected override def doStart() {
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
    }

    protected override def doStop() {
        try {
            val stopFutures = childrenActors map { child => stopActor(child) }
            implicit val s = system
            Await.result(Future.sequence(stopFutures), 150.millis)
            supervisorActor foreach { stopActor }
            log.debug("Stopping the actor system")
            system.shutdown()
            notifyStopped()
        } catch {
            case e =>
                log.error("Exception", e)
                notifyFailed(e)
        }
   }

    def propsFor(actorClass: Class[_ <: Actor]) =
        new Props(new GuiceActorFactory(injector, actorClass))

    protected def stopActor(actorRef: ActorRef) = {
        log.debug("Stopping actor: {}", actorRef.toString())
        Patterns.gracefulStop(actorRef, 100.millis, system)
    }

    private def startTopActor(actorProps: Props, actorName: String) = {
        log.debug("Starting actor {}", actorName)
        Some(system.actorOf(actorProps, actorName))
    }

    protected def startActor(actorProps: Props, actorName: String): ActorRef = {
        val tout = new Timeout(3.seconds)
        supervisorActor map {
            supervisor =>
                log.debug("Starting actor {}", actorName)
                Patterns.ask(supervisor,
                             new StartChild(actorProps, actorName), tout) } map {
            actorRefFuture =>
                Await.result(actorRefFuture.mapTo[ActorRef], tout.duration)
        } getOrElse {
            throw new IllegalArgumentException("No supervisor actor.")
        }
    }

    def initProcessing() {
        log.debug("Sending Initialization message to datapath controller.")
        DatapathController.getRef(system) ! DatapathController.initializeMsg
    }
}
