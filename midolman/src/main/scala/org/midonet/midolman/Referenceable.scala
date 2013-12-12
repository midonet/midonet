/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman

import akka.actor.{ActorSystem, ActorRef}

trait Referenceable {

    def getRef()(implicit system: ActorSystem): ActorRef = {
        system.actorFor(path)
    }

    val Name: String

    protected def Prefix: String = "/user/%s" format supervisorName

    protected def path: String = "%s/%s".format(Prefix, Name)

    protected def supervisorName = "midolman"

}
