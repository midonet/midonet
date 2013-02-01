/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman

/**
 * Basic messages that all the actors should implement.
 */
object Messages {
    case class Ping(value:AnyRef)
    case class Pong(value:AnyRef)
}


trait Event {

}
