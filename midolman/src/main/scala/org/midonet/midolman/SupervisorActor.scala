/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */
package org.midonet.midolman

import akka.actor.{Props, SupervisorStrategy, Actor, Status}
import com.google.inject.Inject

import org.midonet.midolman.logging.ActorLogWithoutPath

/**
 * This actor is responsible for the supervision strategy of all the
 * top-level (Referenceable derived, well-known actors) actors in
 * midolman. All such actors are launched as children of the SupervisorActor
 */
object SupervisorActor extends Referenceable {
    override val Name = supervisorName

    override protected def path: String = Referenceable.getSupervisorPath(Name)

    case class StartChild(props: Props, name: String)
}

class SupervisorActor extends Actor with ActorLogWithoutPath {

    import SupervisorActor._

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    override def postStop() {
        log.info("Supervisor actor is shutting down")
    }

    def receive = {
        case StartChild(props, name) =>
            val result = try {
                context.actorOf(props, name)
            } catch {
                case t: Throwable =>
                    log.error(t, "could not start actor {}", name)
                    Status.Failure(t)
            }
            sender ! result
        case unknown =>
            log.info("received unknown message {}", unknown)
    }
}
