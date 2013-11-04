/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.util.eventloop

import java.util.concurrent.{Future, ScheduledFuture, TimeUnit, Callable}
import com.google.common.util.concurrent.ValueFuture
import scala.{Boolean, Long}
import sun.reflect.generics.reflectiveObjects.NotImplementedException

class CallingThreadReactor extends Reactor {
    def currentTimeMillis(): Long = 0L

    def shutDownNow() { }

    def submit(runnable: Runnable): Future[_] = {
        runnable.run()
        val future = ValueFuture.create[AnyRef]()
        future.set(null)
        future
    }

    def submit[V](work: Callable[V]): Future[V] = {
        val future = ValueFuture.create[V]()
        future.set(work.call())
        future
    }

    def schedule(runnable: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture[_] =
        throw new NotImplementedException

    def schedule[V](work: Callable[V], delay: Long, unit: TimeUnit): ScheduledFuture[V] =
        throw new NotImplementedException

    def isShutDownOrTerminated: Boolean = false
}
