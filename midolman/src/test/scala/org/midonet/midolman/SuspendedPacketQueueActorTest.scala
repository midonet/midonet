package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import akka.testkit.TestActorRef
import org.midonet.midolman.SuspendedPacketQueue.{_CleanCompletedPromises, SuspendOnPromise}
import akka.dispatch.Promise
import org.midonet.util.throttling.ThrottlingGuard
import org.slf4j.LoggerFactory
import org.scalatest.concurrent.Eventually._
import java.util.concurrent.TimeoutException

@RunWith(classOf[JUnitRunner])
class SuspendedPacketQueueActorTest extends SingleActorTestCase {

    var spq: TestActorRef[TestableSPQA] = null
    var throttler: ThrottlingGuard = null
    var log = LoggerFactory.getLogger(classOf[SuspendedPacketQueueActorTest])
    val simSlots = 6

    override def beforeTest() {
        spq = actorFor(() => new TestableSPQA())
        throttler = spq.underlyingActor.throttler
        while (throttler.numTokens() > 0)
            throttler.tokenOut()
        throttler.numTokens() should be === 0
    }

    feature("SuspendedPacketQueueActor manages tokens correctly") {
        scenario("a promise is suspended and released when complete") {
            val cookie = Option(9)
            val promise = Promise[Int]()(actorSystem.dispatcher)

            throttler.tokenIn()
            throttler.numTokens() should be === 1
            when("the message with an incomplete promise is sent")
            spq ! SuspendOnPromise(cookie, promise)
            eventually {
                throttler.numTokens() should be === 0
            }
            and("the ring should contain the promise")
            spq.underlyingActor.peekRing should be === Some((cookie, promise))
            then("the promise succeeds")
            promise.success(1)
            and("the actor should release the token")
            eventually {
                throttler.numTokens() should be === 1
            }
            spq ! _CleanCompletedPromises
            and("the ring should be empty")
            eventually {
                spq.underlyingActor.isRingEmpty should be === true
            }
        }
    }

    feature("Too many suspended promises generate timeouts") {
        scenario("lots of promises are suspended") {

            throttler.numTokens() should be === 0

            var promises = List[Promise[Int]]()
            for (c <- 1 to simSlots-1) {
                val promise = Promise[Int]()(actorSystem.dispatcher)
                promises = promise :: promises
                throttler.tokenIn()
                throttler.numTokens() should be === 1
                spq ! SuspendOnPromise(Some(c), promise)
                eventually {
                    throttler.numTokens() should be === 0
                }
            }
            var failure: Throwable = null
            promises.last.onFailure { case e => failure = e }
            when ("a new promise is suspended")
            val promise = Promise[Int]()(actorSystem.dispatcher)
            throttler.tokenIn()
            throttler.numTokens() should be === 1
            spq ! SuspendOnPromise(Some(simSlots), promise)
            eventually {
                failure should not be null
                failure.getClass should be === classOf[TimeoutException]
            }
            promises.last.isCompleted should be === true
            and ("the failed exception will have recovered a token")
            throttler.numTokens() should be === 1

        }
    }

    class TestableSPQA extends DeduplicationActor {

        override def SUSPENDED_SIM_SLOTS = simSlots

        override def preStart() {
            // cancel the scheduled cleanups
        }

        def isRingEmpty = ring.isEmpty
        def peekRing = ring.peek
    }
}

