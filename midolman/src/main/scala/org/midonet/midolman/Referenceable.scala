/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
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
