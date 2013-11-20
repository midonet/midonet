// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import java.util.concurrent.{TimeoutException, TimeUnit}

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import akka.actor._


import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.util.collection.RingBuffer
import org.midonet.util.throttling.ThrottlingGuard


object SuspendedPacketQueue {
    case object _CleanCompletedPromises
    case class SuspendOnPromise(cookie: Option[Int], future: Promise[_])
}

trait SuspendedPacketQueue extends Actor with ActorLogWithoutPath {
    import SuspendedPacketQueue._

    // not a val because, when overriding in a test, the val will not get its
    // proper value in time for the array below creation
    def SUSPENDED_SIM_SLOTS = 8192

    def throttler: ThrottlingGuard

    protected val ring = new RingBuffer[(Option[Int], Promise[_])](SUSPENDED_SIM_SLOTS, null)

    private def expire(cookie: Option[Int], promise: Promise[_]) {
        if (! promise.isCompleted) {
            log.debug("expiring suspended simulation")
            promise tryFailure new TimeoutException("suspended simulation timed out")
        }
    }

    @tailrec
    private def _cleanup() {
        if (! ring.isEmpty) {
            ring.peek match {
                case Some((cookie, promise)) if promise.isCompleted =>
                    ring.take()
                    _cleanup()
                case _ =>
            }
        }
    }

    override def preStart() {
        super.preStart()
        val interval = Duration(1000, TimeUnit.MILLISECONDS)
        context.system.scheduler.schedule(interval, interval, self,
            _CleanCompletedPromises)
    }

    override def receive = {
        case SuspendOnPromise(cookie, promise) =>
            log.debug("Simulation for {} suspended on a promise", cookie)
            _cleanup()
            if (ring.isFull)
                ring.take() foreach { case (c, p) => expire(c, p) }
            val th = throttler
            cookie foreach { c =>
                th.tokenOut()
                promise.future.onComplete { _ => th.tokenIn() }
            }
            ring.put((cookie, promise))

        case SuspendedPacketQueue._CleanCompletedPromises =>
            _cleanup()
    }

}
