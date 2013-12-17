/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
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

trait Referenceable {

    def getRef()(implicit system: ActorSystem): ActorRef =
        system.actorFor(path)

    val Name: String

    protected def Prefix: String = "/user/%s" format supervisorName

    protected def path: String = "%s/%s".format(Prefix, Name)

    protected def supervisorName = "midolman"
}
