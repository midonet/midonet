/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util.eventloop

import java.util.concurrent.{Delayed, Future, ScheduledFuture, TimeUnit, Callable}

import scala.{Boolean, Long}

import com.google.common.util.concurrent.SettableFuture

class CallingThreadReactor extends Reactor {
    def currentTimeMillis(): Long = 0L

    def shutDownNow() { }

    def submit(runnable: Runnable): Future[_] = {
        runnable.run()
        val future = SettableFuture.create[AnyRef]()
        future.set(null)
        future
    }

    def submit[V](work: Callable[V]): Future[V] = {
        val future = SettableFuture.create[V]()
        future.set(work.call())
        future
    }

    def schedule(runnable: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture[_] =
        schedule(new Callable[Any] {
            override def call() = {
                runnable.run()
                null
            }
        }, 0, null)

    def schedule[V](work: Callable[V], delay: Long, unit: TimeUnit): ScheduledFuture[V] =
        new ScheduledFuture[V] {
            override def getDelay(unit: TimeUnit): Long = 0
            override def compareTo(o: Delayed): Int = 0
            override def isCancelled: Boolean = false
            override def get(): V = work.call()
            override def get(timeout: Long, unit: TimeUnit): V = get()
            override def cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override def isDone: Boolean = true
        }

    def isShutDownOrTerminated: Boolean = false
}
