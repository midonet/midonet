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
            type="akka.testkit.CallingThreadDispatcherConfigurator"
        }
    """)
    val system = ActorSystem("BlockingTest", ConfigFactory.load(config))

    def testOneThread() {
        Thread.sleep(1000)
        val start = Platform.currentTime
        var setTime = start-1
        var elapsed: Long = 0
        val promise = Promise[Int]()(system.dispatcher)
        system.dispatcher.id should be === CallingThreadDispatcher.Id
        system.scheduler.scheduleOnce(1000 milliseconds) { 
            setTime = Platform.currentTime 
        }
        elapsed = setTime - start
        elapsed should be === -1
        elapsed = Platform.currentTime - start
        elapsed should be > 0L
        elapsed should be < 100L
        new Thread { Thread.sleep(3000); promise.complete(Right(1234)) }
        //Await.result(promise, 4000 milliseconds)
        Thread.sleep(4000)
        elapsed = setTime - start
        elapsed should be === -1
        //elapsed should be > 19L
        //elapsed should be <= 21L
    }

}
