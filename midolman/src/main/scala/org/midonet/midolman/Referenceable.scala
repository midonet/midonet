/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import akka.actor.{ActorSystem, ActorRef, ActorContext}

trait Referenceable {

    def getRef()(implicit context: ActorContext): ActorRef = {
        context.actorFor(path)
    }

    def getRef(system: ActorSystem): ActorRef = {
        system.actorFor(path)
    }

    val Name: String

    protected def Prefix: String = "/user/%s" format supervisorName

    protected def path: String = "%s/%s".format(Prefix, Name)

    protected def supervisorName = "midolman"

}
