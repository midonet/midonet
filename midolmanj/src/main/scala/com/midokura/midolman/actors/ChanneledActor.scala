// Copyright 2012 Midokura Inc.

package com.midokura.midolman.actors

import scala.actors.Actor

object ChanneledActor {
   implicit def channel(a: Actor) = new ChanneledActor(a)
}

class ChanneledActor(a: Actor) {
    def !: (msg: Any) = a ! msg
}
