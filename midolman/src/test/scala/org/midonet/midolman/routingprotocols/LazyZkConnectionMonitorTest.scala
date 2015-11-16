/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.routingprotocols

import java.util.{PriorityQueue, Comparator}

import scala.collection.immutable.Queue
import scala.concurrent.duration._

import org.apache.zookeeper.{WatchedEvent, KeeperException}
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, StateAccessException, ZkConnection}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class LazyZkConnectionMonitorTest extends FeatureSpecLike
                                    with Matchers
                                    with BeforeAndAfter
                                    with GivenWhenThen {
    var scheduler: Scheduler = _
    var connWatcher: MockZkConnWatcher = _
    var lazyMonitor: LazyZkConnectionMonitor = _

    var downEvents = 0
    var upEvents = 0

    before {
        downEvents = 0
        upEvents = 0
        scheduler = new Scheduler()
        connWatcher = new MockZkConnWatcher()
        lazyMonitor = new LazyZkConnectionMonitor(() => downEvents += 1,
                                                  () => upEvents += 1,
                                                  connWatcher,
                                                  5 seconds,
                                                  scheduler.clock,
                                                  scheduler.schedule)
    }

    feature ("Zookeeper disconnection events are watched with a delay") {
        scenario ("A disconnection event is delayed") {
            connWatcher.disconnected()
            scheduler.process()
            downEvents should be (0)

            scheduler.clock.time += 4999
            scheduler.process()
            downEvents should be (0)

            scheduler.clock.time += 1
            scheduler.process()
            downEvents should be (1)
        }

        scenario ("A reconnection resets the timer delay") {
            connWatcher.disconnected()
            scheduler.process()

            scheduler.clock.time += 2000
            connWatcher.reconnected()
            scheduler.process()
            upEvents should be (1)

            scheduler.clock.time += 1000
            connWatcher.disconnected()
            scheduler.process()
            downEvents should be (0)

            scheduler.clock.time += 4999
            scheduler.process()
            downEvents should be (0)

            scheduler.clock.time += 1
            scheduler.process()
            downEvents should be (1)
        }

        scenario("disconnection events are muted if reconnected") {
            connWatcher.disconnected()
            scheduler.process()

            scheduler.clock.time += 4999
            connWatcher.reconnected()
            scheduler.process()

            scheduler.clock.time += 1
            scheduler.process()

            scheduler.clock.time += 5000
            scheduler.process()

            downEvents should be (0)
            upEvents should be (1)
        }
    }
}

class Scheduler() {
    val clock = UnixClock.MOCK
    private val comparator = new Comparator[(Long, Runnable)] {
        override def compare(a: (Long, Runnable), b: (Long, Runnable)) = java.lang.Long.compare(a._1, b._1)
    }

    val events: PriorityQueue[(Long, Runnable)] = new PriorityQueue(16, comparator)

    def schedule(when: FiniteDuration, r: Runnable): Unit = {
        events.add((clock.time + when.toMillis, r))
    }

    def process(): Unit = {
        while (!events.isEmpty && events.peek._1 <= clock.time) {
            events.poll()._2.run()
        }
    }
}

class MockZkConnWatcher extends ZkConnectionAwareWatcher {
    var onReconnect: List[Runnable] = List.empty
    var onDisconnect: List[Runnable] = List.empty

    override def scheduleOnReconnect(r: Runnable): Unit = {
        onReconnect ::= r
    }

    override def scheduleOnDisconnect(r: Runnable): Unit = {
        onDisconnect ::= r
    }

    def disconnected(): Unit = {
        val cbs = onDisconnect
        onDisconnect = List.empty
        for (cb <- cbs) {
            cb.run()
        }
    }

    def reconnected(): Unit = {
        val cbs = onReconnect
        onReconnect = List.empty
        for (cb <- cbs) {
            cb.run()
        }
    }

    override def setZkConnection(conn: ZkConnection) {}
    override def handleDisconnect(r: Runnable) {}
    override def handleTimeout(r: Runnable) {}
    override def handleError(objectDesc: String, retry: Runnable, e: KeeperException) {}
    override def handleError(objectDesc: String, retry: Runnable, e: StateAccessException) {}
    override def process(event: WatchedEvent): Unit = {}
}
