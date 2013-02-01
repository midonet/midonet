package org.midonet.midolman.logging

import akka.actor.{ActorSystem, Actor}
import akka.event.LogSource

trait ActorLogWithoutPath { this: Actor ⇒
    // default log source for an actor, empty string. Default is the actor path
    implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
        def genString(o: AnyRef): String = ""

        // Ignore the actor system since we use just one
        override def genString(a: AnyRef, s: ActorSystem) = ""
    }
    val log = akka.event.Logging(context.system.eventStream, this)
}
