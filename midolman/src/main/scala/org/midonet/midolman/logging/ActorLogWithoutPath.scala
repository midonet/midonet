package org.midonet.midolman.logging

import akka.actor.Actor
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory


trait ActorLogWithoutPath { this: Actor ⇒
    def logSource = self.path.name
    val log = Logger(LoggerFactory.getLogger(logSource))
}
