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

import akka.actor.{ActorRef, ActorSystem, ScalaActorRef}
import akka.pattern.AskableActorRef

trait ReferenceableSupport {
    implicit def toActorRef(r: Referenceable)(implicit system: ActorSystem)
    : ActorRef = r.getRef()

    // Needed because Scala doesn't chain implicit conversions.

    implicit def toScalaActorRef(r: Referenceable)(implicit system: ActorSystem)
    : ScalaActorRef = r.getRef()
    implicit def toAskableActorRef(r: Referenceable)(implicit system: ActorSystem)
    : AskableActorRef = r.getRef()
}

object Referenceable {
    def getSupervisorPath(supervisorName: String) =
        "/user/" + supervisorName

    def getReferenceablePath(supervisorName: String, actorName: String) =
        getSupervisorPath(supervisorName) + "/" + actorName
}

trait Referenceable {

    def getRef()(implicit system: ActorSystem): ActorRef =
        system.actorFor(path)

    val Name: String

    protected def path: String =
        Referenceable.getReferenceablePath(supervisorName, Name)

    protected def supervisorName = "midolman"
}
