/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman

import java.util.concurrent.ThreadFactory

import scala.annotation.tailrec
import scala.collection.mutable.PriorityQueue
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import akka.actor.{Cancellable, Scheduler}
import akka.event.LoggingAdapter
import com.typesafe.config.Config
import scala.util.Try

/**
 * This is an implementation of the Scheduler interface that replaces Akka's
 * default one (see MockMidolmanActorsService), intented to be used in unit
 * tests where we don't want asynchronous code to run, for better control and
 * less flakiness.
 *
 * This Scheduler prevents asynchronous execution of the scheduled runnables by
 * keeping them in a queue. Consumers of this class must instead dequeue the
 * runnables via the pop() function and either execute or discard them. This
 * guarantees that any scheduled runnables are executed when the driver of
 * tests wants:
 *
 * val mockScheduler = actorSystem.scheduler.asInstanceOf[MockScheduler]
 * do_something_that_schedules_a_runnable(actorSystem)
 * assert_state()
 * mockScheduler.pop() foreach { _.run() } // or: mockScheduler.runAll()
 * assert_state_after_running_scheduled_item()
 *
 * The ctor's parameters are not used, but they are required by Akka.
 */

class MockScheduler(config: Config,
                    log: LoggingAdapter,
                    threadFactory: ThreadFactory) extends Scheduler {
    override def maxFrequency: Double = 1

    private case class ScheduledRunnable(runnable: Runnable, shouldStartAt: Long,
                                         interval: Option[FiniteDuration] = None) {
        var cancelled: Boolean = false
    }

    implicit private val ordering = new Ordering[ScheduledRunnable] {
        override def compare(x: ScheduledRunnable, y: ScheduledRunnable): Int = {
            if ((x eq null) && (y eq null))
                0
            else if (x eq null)
                1
            else if (y eq null)
                -1
            else
                implicitly[Ordering[Long]].compare(x.shouldStartAt, y.shouldStartAt)
        }
    }

    private val scheduledRunnables = PriorityQueue[ScheduledRunnable]()

    final def pop(): Option[Runnable] =
        Try { scheduledRunnables.dequeue() }.toOption flatMap { sr =>
            if (sr.cancelled) {
                pop()
            } else {
                ensureReschedule(sr)
                Some(sr.runnable)
            }
        }

    final def runAll(): Unit =
        scheduledRunnables.dequeueAll filterNot (_.cancelled) foreach { sr =>
            ensureReschedule(sr)
            sr.runnable.run()
        }

    final def size = scheduledRunnables count (!_.cancelled)

    private final def ensureReschedule(sr: ScheduledRunnable): Unit =
        sr.interval match {
            case s@Some(int) =>
                val nsr = ScheduledRunnable(sr.runnable,
                    sr.shouldStartAt + int.toNanos,
                    s)
                scheduledRunnables += nsr
            case None =>
        }

    override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)
                             (implicit executor: ExecutionContext): Cancellable =
        doSchedule(runnable, delay, None)

    override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration,
                          runnable: Runnable)
                         (implicit executor: ExecutionContext): Cancellable =
        doSchedule(runnable, initialDelay, Some(interval))

    private def doSchedule(runnable: Runnable, delay: FiniteDuration,
                           interval: Option[FiniteDuration]): Cancellable = {
        val sr = ScheduledRunnable(runnable, System.nanoTime() + delay.toNanos,
                                   interval)
        scheduledRunnables += sr
        new Cancellable {
            override def cancel(): Boolean = {
                sr.cancelled = true
                true
            }

            override def isCancelled: Boolean =
                sr.cancelled
        }
    }
}
