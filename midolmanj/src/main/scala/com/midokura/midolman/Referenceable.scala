/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman

import akka.actor.{ActorSystem, ActorRef, ActorContext}


/**
 * Copyright 2012 Midokura Europe SARL
 * User: Rossella Sblendido <rossella@midokura.com>
 * Date: 8/30/12
 */

trait Referenceable {

    def getRef()(implicit context: ActorContext): ActorRef = {
        context.actorFor("/user/%s" format Name)
    }

    def getRef(system: ActorSystem): ActorRef = {
        system.actorFor("/user/%s" format Name)
    }

    def Name(): String

}
