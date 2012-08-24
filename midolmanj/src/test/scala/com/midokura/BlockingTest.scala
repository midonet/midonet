// Copyright 2012 Midokura Inc.

/*
 * Test plan:
 *    - Create executor with single thread.
 *    - Schedule operations on the executor for T+1.0s and T+1.5s.
 *    - The first operation records its start time, waits for 5.3s,
 *          and records its stop time.
 *    - The second operation only records its start time.
 *
 * If the first operation's wait blocked the thread, then the second
 * operation's recorded time will be 6.3s, but if it only blocked the
 * execution context and returned the thread to the executor, the recorded
 * time will be 1.5s.
 */

package com.midokura

import akka.actor.{Actor, ActorSystem, Props}
import akka.dispatch.{Await, Promise}
import akka.dispatch.Future.flow
import akka.event.Logging
import akka.pattern.pipe
import akka.testkit.CallingThreadDispatcher
import akka.util.duration._
import compat.Platform
import concurrent.ops.spawn
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, Suite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers


object BlockingTest {
    var instanceNum = 0
    def newInstanceName() = {
        instanceNum += 1
        "BlockingTest" + instanceNum
    }
}

@RunWith(classOf[JUnitRunner])
class BlockingTest extends Suite with ShouldMatchers with OneInstancePerTest {
    import BlockingTest._

    val config = ConfigFactory.parseString("""
        akka.actor.default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
                parallelism-max = 1
            }
        }
    """)
    val system = ActorSystem(newInstanceName, ConfigFactory.load(config))
    val initialDelay = 1000L
    val op2Start = 1500L
    val sleepTime = 5300L
    val margin = 150L

    // Hack:  Class variable used to record thunk-completion time.
    // Relies on each test having its own instance of BlockingTest.
    @volatile var time3 = 0L
    private val time3UpdatingActor = system.actorOf(Props(new Updater))
    private class Updater extends Actor {
        val log = Logging(system, this)

        def receive = {
            case 1234 => log.info("received #1234"); time3 = Platform.currentTime
            case x => log.info("received other {}", x)
        }

        override def preStart() { log.info("Starting time3UpdatingActor.") }
    }

    private def spawnFlowPromiseThread(promise: Promise[Int]) {
        spawn {    
            Thread.sleep(initialDelay+sleepTime)
            flow {
                promise.success(1234)
            }(system.dispatcher)
        }
    }

    private def spawnRawPromiseThread(promise: Promise[Int]) {
        spawn {
            Thread.sleep(initialDelay+sleepTime)
            promise.success(1234)
        }
    }

    def testThreadSleep() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, () => { 
            Thread.sleep(sleepTime)
            time3UpdatingActor ! 1234
        }, true, (_) => ())
    }

    def testAwaitResult() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, () => {
            time3UpdatingActor ! Await.result(promise, 7 seconds)
        }, true, spawnRawPromiseThread)
    }

    def testNoOp() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise,
                         () => (time3 = Platform.currentTime+sleepTime),
                         false, (_) => ())
    }

    // This fails, because the thunk never stops waiting for the promise.
    def IGNOREtestFlowBlock() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, () => {
            Await.result(flow {
                time3UpdatingActor ! promise()
            }(system.dispatcher), 8 seconds)
        }, true, spawnFlowPromiseThread)
    }

    def testPipeTo() {
        val promise = Promise[Int]()(system.dispatcher)
        checkForBlocking(promise, () => (promise pipeTo time3UpdatingActor),
                         false, spawnRawPromiseThread)
    }

    private def checkForBlocking(promise: Promise[Int],
                                 thunk: () => Unit, expectBlocking: Boolean,
                                 threadSpawner: (Promise[Int]) => Unit) {
        Thread.sleep(1000)
        val start = Platform.currentTime
        @volatile var time1 = start-1
        @volatile var time2 = start-1
        time3 = start-1
        var elapsed = 0L
        assert(system.dispatcher.id != CallingThreadDispatcher.Id)
        system.scheduler.scheduleOnce(initialDelay milliseconds) { 
            time1 = Platform.currentTime 
            thunk()
        }
        system.scheduler.scheduleOnce(op2Start milliseconds) { 
            time2 = Platform.currentTime 
        }

        threadSpawner(promise)

        // The executor's one thread runs the first scheduled operation, and has
        // to wait for it to complete before it can run the second scheduled
        // operation, late if the first op blocked.

        elapsed = Platform.currentTime - start
        elapsed should be >= 0L
        elapsed should be <= margin
        elapsed = time1 - start
        elapsed should be === -1
        elapsed = time2 - start
        elapsed should be === -1
        Thread.sleep(2000)
        elapsed = time1 - start
        elapsed should be >= initialDelay
        elapsed should be <= initialDelay + margin
        elapsed = time2 - start
        if (expectBlocking) {
            elapsed should be === -1
        } else {
            elapsed should be >= op2Start
            elapsed should be <= op2Start + margin
        }
        Thread.sleep(8000)
        if (expectBlocking) {
            elapsed = time2 - start
            elapsed should be >= initialDelay + sleepTime
            elapsed should be <= initialDelay + sleepTime + margin
        }
        elapsed = time3 - start
        elapsed should be >= initialDelay + sleepTime
        elapsed should be <= initialDelay + sleepTime + margin
    }

}
