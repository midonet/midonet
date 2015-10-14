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
package org.midonet.midolman

import scala.concurrent.duration._

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
                import context.{system, dispatcher}
                context.actorOf(props, name).awaitStart(30 seconds)
            } catch {
                case t: Throwable =>
                    log.error(s"could not start actor $name", t)
                    Status.Failure(t)
            }
            sender ! result

        case unknown => log.info(s"received unknown message $unknown")
    }
}
