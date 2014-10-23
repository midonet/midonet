/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.cluster.util

import java.util.concurrent.{Callable, ExecutorService, ForkJoinPool, Future, TimeUnit}

import rx.internal.operators.OperatorDoOnUnsubscribe

import org.midonet.util.functors._

import ch.qos.logback.classic.Level
import org.apache.curator.framework.recipes.cache.ChildData
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory
import rx.Observable
import rx.observers.{TestObserver, TestSubscriber}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

@RunWith(classOf[JUnitRunner])
class ObservablePathChildrenCacheTest extends Suite
                                      with CuratorTestFramework
                                      with Matchers {
    val log = LoggerFactory.getLogger(classOf[ObservablePathChildrenCache])

    def makePaths(n: Int): Map[String, String] = makePaths(0, n)

    def makePaths(start: Int, end: Int): Map[String, String] = {
        var data = Map.empty[String, String]
        start.until(end) foreach { i =>
            val childPath = ZK_ROOT + "/" + i
            val childData = i.toString
            data += (childPath -> childData)
            curator.create().inBackground()
                   .forPath(childPath, childData.getBytes)
        }
        data
    }

    /** Subscribes to an ObservablePathChildrenCache and also to every child
      * observable that appears, accumulating all the data received.
      */
    class ChildDataAccumulator extends TestSubscriber[Observable[ChildData]] {

        val childObserver = new TestObserver[ChildData]()
        val children = ListBuffer[TestObserver[ChildData]]()

        override def onNext(o: Observable[ChildData]) {
            super.onNext(o)
            o.subscribe(childObserver)
            val to = new TestObserver[ChildData]()
            o.subscribe(to)
            children += to

        }

        def extractData(): List[String] =
            childObserver.getOnNextEvents.map(d => new String(d.getData)).toList

    }

    /** Verifes all the child observables and ensures that they contain exactly
      * one element with data that matches the path's suffix.
      *
      * Also verifies that all child observables and the parent one are not
      * completed or received any errors. */
    private def checkChildren(acc: ChildDataAccumulator) {
        val allData = acc.children.map { o =>
            o.getOnCompletedEvents should be (empty)
            o.getOnErrorEvents should be (empty)
            val cd = o.getOnNextEvents.get(0)
            cd.getPath should endWith(new String(cd.getData))
            cd
        }
        acc.childObserver
            .getOnNextEvents should contain theSameElementsAs allData
        acc.childObserver.getOnErrorEvents should be (empty)
    }


    /* Prepares a node with some children, connects, fetches the observable and
     * expects that we receive an observable for each children that is already
     * primed with the initial state of the node. */
    def testChildObservablesArePrimed() {
        val nItems = 10
        val collector = new ChildDataAccumulator()
        val opcc = new ObservablePathChildrenCache(curator)

        makePaths(nItems)         // preseed
        opcc connect ZK_ROOT      // connect
        opcc subscribe collector  // subscribe

        Thread sleep 1000

        val children = collector.getOnNextEvents

        children should have size nItems

        checkChildren(collector)

        opcc close()
    }

    /* Ensures that deleted children get their observables completed */
    def testChildObservableCompletesOnNodeDeletion() {

        makePaths(2)
        val collector = new ChildDataAccumulator()
        val opcc = new ObservablePathChildrenCache(curator)
        opcc connect ZK_ROOT
        opcc subscribe collector
        Thread sleep 500
        collector.getOnNextEvents should have size 2 // data is there

        // Delete one node
        val deletedPath = ZK_ROOT + "/1"
        curator.delete().forPath(deletedPath)

        Thread sleep 100  // let Curator catch up

        // Order is not deterministic, so let's find out which one is deleted
        val (deleted, kept) = collector.children.partition (
            _.getOnNextEvents.head.getPath.equals(deletedPath)
        )
        kept.head.getOnCompletedEvents should be (empty)
        deleted.head.getOnCompletedEvents should not be empty
        // The overall collector should not complete, since there is one active
        // child
        collector.getOnCompletedEvents should be (empty)
    }

    /* Creates a node with initial state, asserts that we get the primed
     * observable and continues making a bunch of updates to assert at the end
     * that both the child observable, and the direct access to the data did
     * converge to the last state. */
    def testDataUpdatesReceived() {
        val nodeData = makePaths(1)
        val childData = nodeData.values.head
        val collector = new ChildDataAccumulator()
        val opcc = new ObservablePathChildrenCache(curator)

        nodeData should not be null

        opcc connect ZK_ROOT
        opcc subscribe collector

        Thread sleep 500

        checkChildren(collector)

        // Create a new observer for a child, and subscribe
        val _1 = new TestObserver[ChildData]()
        opcc.observableChild(nodeData.keys.head).subscribe(_1)

        Thread sleep 500

        collector.extractData().head shouldEqual childData
        childData shouldEqual new String(_1.getOnNextEvents.head.getData)

        // Let's trigger some updates
        val path = nodeData.keys.head
        curator.setData().forPath(path, "a".getBytes)
        curator.setData().forPath(path, "b".getBytes)
        curator.setData().forPath(path, "c".getBytes)

        Thread sleep 500

        "c" shouldEqual new String(opcc.child(path).getData)

        // We don't necessarily get all updates, but at least converge to "c"
        // on the last one
        val evts = _1.getOnNextEvents
        "c" shouldEqual new String(evts.last.getData)

        collector.extractData() should contain ("c")

    }

    /* Ensure that whenever a new child is created, its observable appears on
     * the top level observable. */
    def testCreatedChildrenGetNewObservable() {
        val nodeData = makePaths(1)
        val oldChildData = nodeData.values.head
        val collector = new ChildDataAccumulator()
        val opcc = new ObservablePathChildrenCache(curator)

        opcc connect ZK_ROOT
        opcc subscribe collector

        Thread sleep 500

        val newChildData = "new"
        val newChildPath = ZK_ROOT + "/newchild"
        curator.create().forPath(ZK_ROOT + "/newchild", newChildData.getBytes)

        Thread sleep 500

        collector.getOnErrorEvents should be (empty)
        collector.getOnCompletedEvents should be (empty)

        val children = collector.getOnNextEvents
        children should have size 2
        collector.extractData() should contain (oldChildData)
        collector.extractData() should contain (newChildData)

        // New data is stored
        newChildData shouldEqual new String(opcc.child(newChildPath).getData)
    }

    /* This tests ensures that there are no gaps in child observables if
     * subscriptions and new children appear concurrently. This is focused
     * mostly on syncing the subscribre() and newChild() handling. */
    def testRaceConditionOnSubscription() {

        // Avoid spam on the Curator lib, hits performance
        val zkLogger = LoggerFactory.getLogger("org.apache.zookeeper")
                                    .asInstanceOf[ch.qos.logback.classic.Logger]
        zkLogger.setLevel(Level.toLevel("INFO"))

        val nInitial = 50  // children precreated
        val nTotal = 1000   // total child count to reach during subscriptions
        val nSubs = 20     // number of subscribers

        makePaths(nInitial)

        val opcc = new ObservablePathChildrenCache(curator)

        opcc.connect(ZK_ROOT)

        // Let the OPCC catch up to the initial state
        var maxRetries = 20
        while (opcc.allChildren().size < nInitial) {
            if (maxRetries <= 0) {
                fail("Timeout waiting for storage preseeding")
            }
            maxRetries -= 1
            Thread.sleep(200)
        }
        opcc.allChildren should have size nInitial

        // This thread will add elements until reaching nTotal children
        val step = 5
        val updater = new Runnable() {
            override def run() {
                log.info("I'm creating additional paths..")
                try {
                    nInitial until (nTotal, step) foreach { i =>
                        makePaths(i, i + step)
                        Thread sleep 10
                    }
                } catch {
                    case _: Throwable => fail("Cannot create paths")
                }
                log.info("All children created")
            }
        }

        val es: ExecutorService = new ForkJoinPool(nSubs + 1)

        // As we are updating, we will create a bunch of subscribers that will
        // subscribe to the observable and accumulate elements received in
        // children
        val subscribers = new ListBuffer[Future[List[String]]]()
        0 until nSubs foreach { i =>
            val subscriber = new Callable[List[String]]() {
                    override def call() = {
                        val acc = new ChildDataAccumulator()
                        opcc subscribe acc
                        // Wait until the ObservablePathChildrenCache is closed
                        while (acc.getOnCompletedEvents.isEmpty) {
                            Thread.`yield`()
                        }
                        acc.extractData()
                    }
                }

            subscribers add es.submit(subscriber)

            Thread.sleep(10)

            if (i == nSubs/10) { // Launch the updater while subscribers come
                es.submit(updater)
            }

        }

        maxRetries = 20
        while (opcc.allChildren().size() < nTotal) {
            if (maxRetries <= 0) {
                fail("Timeout waiting for child nodes to be created")
            }
            maxRetries -= 1
            Thread.sleep(1000)
        }

        opcc.allChildren should have size nTotal

        // Extract all elements present in the cache
        val children = opcc.allChildren()
                           .map(childData => new String(childData.getData))

        children should have size nTotal

        opcc.close() // we can close to complete the children

        subscribers.foreach { s =>
            val received = s.get(2, TimeUnit.SECONDS)
            s.isDone shouldBe true
            children.diff(received.toSeq) should be (empty)
        }

        log.debug("All streams are correct")

        es.shutdown()
        try {
            // Wait a while for existing tasks to terminate
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                es.shutdownNow()
                if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Pool didn't terminate")
                }
            }
        } catch {
            case e: InterruptedException =>
                es.shutdownNow()
                Thread.currentThread().interrupt()
        }
    }
}
