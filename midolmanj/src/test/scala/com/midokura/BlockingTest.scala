// Copyright 2012 Midokura Inc.

/*
 * Test plan:  Create dispatcher with single thread
 *         (Verify it's a single thread by:
 *                 Scheduling an operation in 10 ms, sleeping for 20ms, verify
 *                 operation doesn't happen until 20ms, not 10ms.)
 * Schedule operation in 10ms.
 * Wait on a future.
 * Complete future in 20ms.
 * Verify operation happened in 10ms with flow block, but in 20ms with Await.
 */

package com.midokura

import akka.actor.ActorSystem
import akka.dispatch.{Await, Promise}
import akka.testkit.CallingThreadDispatcher
import akka.util.duration._
import compat.Platform
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, Suite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers


@RunWith(classOf[JUnitRunner])
class BlockingTest extends Suite with ShouldMatchers with OneInstancePerTest {

    val config = ConfigFactory.parseString("""
        akka.actor.default-dispatcher {
            # type="akka.testkit.CallingThreadDispatcherConfigurator"
            executor = "fork-join-executor"
            fork-join-executor {
                parallelism-max = 1
            }
        }
    """)
    val system = ActorSystem("BlockingTest", ConfigFactory.load(config))
    //val system = ActorSystem("BlockingTest")

    def testOneThread() {
        Thread.sleep(1000)
        val start = Platform.currentTime
        @volatile var time1 = start-1
        @volatile var time2 = start-1
        var elapsed: Long = 0
        val promise = Promise[Int]()(system.dispatcher)
        //system.dispatcher.id should be === CallingThreadDispatcher.Id
        assert(system.dispatcher.id != CallingThreadDispatcher.Id)
        system.scheduler.scheduleOnce(1000 milliseconds) { 
            time1 = Platform.currentTime 
            Thread.sleep(5300)
        }
        system.scheduler.scheduleOnce(1500 milliseconds) { 
            time2 = Platform.currentTime 
        }
        //new Thread { Thread.sleep(3000); promise.complete(Right(1234)) }
        elapsed = time1 - start
        elapsed should be === -1
        elapsed = time2 - start
        elapsed should be === -1
        elapsed = Platform.currentTime - start
        elapsed should be > 0L
        elapsed should be < 100L
        //Await.result(promise, 4000 milliseconds)
        Thread.sleep(2000)
        elapsed = time1 - start
        elapsed should be >= 1000L
        elapsed should be <= 1100L
        elapsed = time2 - start
        elapsed should be === -1
        Thread.sleep(6000)
        elapsed = time2 - start
        elapsed should be >= 6300L
        elapsed should be <= 6400L
    }

}
