package org.midonet.midolman

import scala.concurrent._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._
import org.slf4j.LoggerFactory

import org.midonet.midolman.SuspendedPacketQueue.{_CleanCompletedPromises, SuspendOnPromise}
import org.midonet.util.throttling.ThrottlingGuard
import org.midonet.midolman.services.MessageAccumulator

@RunWith(classOf[JUnitRunner])
class SuspendedPacketQueueActorTest extends FeatureSpec
                                    with Matchers with GivenWhenThen
                                    with BeforeAndAfter with MockMidolmanActors
                                    with OneInstancePerTest with MidolmanServices {

    var spq: TestableSPQA = _
    var throttler: ThrottlingGuard = null
    var log = LoggerFactory.getLogger(classOf[SuspendedPacketQueueActorTest])
    val simSlots = 6

    override def registerActors = List(
        DeduplicationActor -> (() => new TestableSPQA))

    override def beforeTest() {
        spq = DeduplicationActor.as[TestableSPQA]
        throttler = spq.throttler
        while (throttler.numTokens() > 0)
            throttler.tokenOut()
        throttler.numTokens() should be (0)
    }

    feature("SuspendedPacketQueueActor manages tokens correctly") {
        scenario("a promise is suspended And released when complete") {
            val cookie = Option(9)
            val p = promise[Int]()

            throttler.tokenIn()
            throttler.numTokens() should be (1)
            When("the message with an incomplete promise is sent")
            DeduplicationActor ! SuspendOnPromise(cookie, p)
            eventually {
                throttler.numTokens() should be (0)
            }
            And("the ring should contain the promise")
            spq.peekRing should be (Some((cookie, p)))
            Then("the promise succeeds")
            p success 1
            And("the actor should release the token")
            eventually {
                throttler.numTokens() should be (1)
            }
            DeduplicationActor ! _CleanCompletedPromises
            And("the ring should be empty")
            eventually {
                spq.isRingEmpty should be (true)
            }
        }
    }

    feature("Too many suspended promises generate timeouts") {
        scenario("lots of promises are suspended") {

            throttler.numTokens() should be (0)

            var promises = List[Promise[Int]]()
            for (c <- 1 to simSlots-1) {
                val p = promise[Int]()
                promises = p :: promises
                throttler.tokenIn()
                throttler.numTokens() should be (1)
                DeduplicationActor ! SuspendOnPromise(Some(c), p)
                eventually {
                    throttler.numTokens() should be (0)
                }
            }
            var failure: Throwable = null
            promises.last.future.onFailure { case e: Throwable => failure = e }
            When ("a new promise is suspended")
            val p = promise[Int]()
            throttler.tokenIn()
            throttler.numTokens() should be (1)
            DeduplicationActor ! SuspendOnPromise(Some(simSlots), p)
            eventually {
                failure should not be null
                failure.getClass should be (classOf[TimeoutException])
            }
            promises.last.isCompleted should be (true)
            And ("the failed exception will have recovered a token")
            throttler.numTokens() should be (1)

        }
    }

    class TestableSPQA extends DeduplicationActor with MessageAccumulator {

        override def SUSPENDED_SIM_SLOTS = simSlots

        override def preStart() {
            // cancel the scheduled cleanups
        }

        def isRingEmpty = ring.isEmpty
        def peekRing = ring.peek
    }
}

