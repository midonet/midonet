/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.recycler

import java.util.UUID
import java.util.concurrent.{TimeUnit, _}

import scala.util.Try

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observable
import rx.observers.TestObserver

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.models.Topology.{Host, Network, Port, Router}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.{FakeDiscovery, MidonetDiscovery}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.minion.Context
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.eventloop.Reactor
import org.midonet.util.{MidonetEventually, MockUnixClock, UnixClock}

@RunWith(classOf[JUnitRunner])
class RecyclerTest extends FeatureSpec with MidonetBackendTest with Matchers
                           with GivenWhenThen with MidonetEventually {

    private class AutoScheduledExecutorService(var executionCount: Int = 1)
        extends SameThreadButAfterExecutorService with ScheduledExecutorService {

        private final def completedFuture[T](result: T) = new ScheduledFuture[T] {
            override def getDelay(unit: TimeUnit): Long = 0
            override def compareTo(o: Delayed): Int = 0
            override def isCancelled: Boolean = false
            override def get(): T = result
            override def get(timeout: Long, unit: TimeUnit): T = get()
            override def cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override def isDone: Boolean = true
        }

        override def schedule(command: Runnable, delay: Long, unit: TimeUnit)
        : ScheduledFuture[_] = {
            if (executionCount > 0) {
                executionCount -= 1
                super.submit(command)
            }
            completedFuture(null)
        }

        override def schedule[V](callable: Callable[V], delay: Long,
                                 unit: TimeUnit)
        : ScheduledFuture[V] = {
            if (executionCount > 0) {
                executionCount -= 1
                super.submit(callable)
            }
            completedFuture[V](null.asInstanceOf[V])
        }

        override def scheduleAtFixedRate(command: Runnable, initialDelay: Long,
                                         period: Long, unit: TimeUnit)
        : ScheduledFuture[_] = ???

        override def scheduleWithFixedDelay(command: Runnable, initialDelay: Long,
                                            delay: Long, unit: TimeUnit)
        : ScheduledFuture[_] = ???
    }

    private class TestableRecycler(context: Context, backend: MidonetBackend,
                                   executor: ScheduledExecutorService,
                                   config: ClusterConfig)
        extends Recycler(context, backend, executor, config) {

        val mockedClock = clock.asInstanceOf[MockUnixClock]
    }

    private var store: ZookeeperObjectMapper = _
    private var backend: MidonetBackend = _
    private var clusterConfig: ClusterConfig = _

    protected override def setup(): Unit = {
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        store = new ZookeeperObjectMapper(
            config, MidonetBackend.ClusterNamespaceId.toString, curator,
            curator, stateTables, reactor, new StorageMetrics(new MetricRegistry))
        MidonetBackend.setupBindings(store, store)
        backend = new MidonetBackend {
            override def stateStore: StateStorage = RecyclerTest.this.store
            override def store: Storage = RecyclerTest.this.store
            override def curator: CuratorFramework = RecyclerTest.this.curator
            override def failFastConnectionState: Observable[ConnectionState] = null
            override def stateTableStore: StateTableStorage = null
            override def failFastCurator: CuratorFramework = RecyclerTest.this.curator
            override def reactor: Reactor = null
            override def doStop(): Unit = { }
            override def doStart(): Unit = { }
            override def connectionState: Observable[ConnectionState] = Observable.never()
            override def stateTableClient: StateTableClient = null
            override val discovery: MidonetDiscovery = new FakeDiscovery
        }
        clusterConfig = new ClusterConfig(ConfigFactory.parseString(
            """
              |cluster.recycler.enabled : true
              |cluster.recycler.interval : 1h
              |cluster.recycler.throttling_rate : 1000000001
              |cluster.recycler.shutdown_interval : 10s
            """.stripMargin)
        )
    }

    private def newRecycler(executor: ScheduledExecutorService =
                                new AutoScheduledExecutorService): TestableRecycler = {
        new TestableRecycler(context = null, backend, executor, clusterConfig)
    }

    /* This method blocks until the creation time of the given path is not
       the system's current time. Otherwise the recycler will fail to remove
       the object identified by this path, as its expecting it to be
       strictly in the past.
    */
    private def waitForExpiry(path: String): Unit = {
        val stat = new Stat()
        eventually {
            curator.getData.storingStatIn(stat).forPath(path)
            stat.getCtime should be < System.currentTimeMillis()
        }
    }

    feature("Recycler lifecycle") {
        scenario("Service starts and stops") {
            Given("A recycling service")
            val recycler = newRecycler()

            Then("The recycler is enabled")
            recycler.isEnabled shouldBe true

            And("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }
    }

    feature("Recycler schedules recycling tasks") {
        scenario("Skips recycling if current time is less that NSDB timestamp") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time =
                stat.getMtime + clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should be skipped")
            result.isFailure shouldBe true
            result.failed.get.getClass shouldBe classOf[RecyclingCanceledException]

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }

        scenario("Skips recycling if schedule time is equal to NSDB timestamp") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time =
                stat.getMtime + clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should be skipped")
            result.isFailure shouldBe true
            result.failed.get.getClass shouldBe classOf[RecyclingCanceledException]

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }

        scenario("Runs recycling if schedule time is greater than NSDB timestamp") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            recycler.mockedClock.time =
                stat.getMtime + clusterConfig.recycler.interval.toMillis + 1

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            val currentTimestamp = stat.getMtime

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The NSDB timestamp should be updated")
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            currentTimestamp should be < stat.getMtime
        }

        scenario("Runs recycling multiple times") {
            Given("A recycling service")
            val recycler = newRecycler(new AutoScheduledExecutorService(2))

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            recycler.mockedClock.time =
                stat.getMtime + clusterConfig.recycler.interval.toMillis + 1

            And("A task observer")
            val observer = new TestObserver[Try[RecyclingContext]]()
            recycler.tasks.subscribe(observer)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            recycler.tasks.toBlocking.first()

            And("The task should be run twice")
            observer.getOnNextEvents should have size 2

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

        }

        scenario("Recycle requires ZOOM backend") {
            Given("In memory storage backend")
            val s = new InMemoryStorage
            val backend = new MidonetBackend {
                override def stateStore: StateStorage = s
                override def store: Storage = s
                override def curator: CuratorFramework = RecyclerTest.this.curator
                override def failFastConnectionState: Observable[ConnectionState] = null
                override def stateTableStore: StateTableStorage = null
                override def failFastCurator: CuratorFramework = RecyclerTest.this.curator
                override def reactor: Reactor = null
                override def doStop(): Unit = { }
                override def doStart(): Unit = { }
                override def connectionState: Observable[ConnectionState] = Observable.never()
                override def stateTableClient: StateTableClient = null
                override val discovery: MidonetDiscovery = new FakeDiscovery
            }

            And("A recycling service")
            val recycler = new Recycler(context = null, backend,
                                        new AutoScheduledExecutorService,
                                        clusterConfig)

            When("The recycler starting should fail")
            intercept[IllegalStateException] {
                recycler.startAsync().awaitRunning()
            }
        }

        scenario("Recycler handles NSDB path errors") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            recycler.mockedClock.time =
                stat.getMtime + clusterConfig.recycler.interval.toMillis + 1

            When("Deleting the NSDB path")
            curator.delete().deletingChildrenIfNeeded().forPath(store.basePath)

            And("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should fail")
            val result = recycler.tasks.toBlocking.first()
            result.isFailure shouldBe true
            result.failed.get.getClass shouldBe classOf[RecyclingStorageException]

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }
    }

    feature("Recycler deletes namespaces") {
        scenario("Namespaces for non-existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for a namespace")
            val namespace = UUID.randomUUID().toString
            val path = store.stateNamespacePath(namespace)
            curator.create().forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedNamespaces shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) shouldBe null
        }

        scenario("Namespaces for existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for a namespace with host")
            val id = UUID.randomUUID()
            curator.create().forPath(store.stateNamespacePath(id.toString))
            curator.create().forPath(store.objectPath(classOf[Host], id))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedNamespaces shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should not be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(id.toString)) should not be null
        }

        scenario("Namespaces with children") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for a namespace with host")
            val id = UUID.randomUUID()
            curator.create().forPath(store.stateNamespacePath(id.toString))
            curator.create().forPath(store.stateNamespacePath(id.toString) + "/child")
            curator.create().forPath(store.objectPath(classOf[Host], id))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedNamespaces shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should not be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(id.toString)) should not be null
        }

        scenario("The cluster namespace") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedNamespaces shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should exist")
            curator.checkExists()
                .forPath(store.stateNamespacePath(
                    MidonetBackend.ClusterNamespaceId.toString)) should not be null
        }
    }

    feature("Recycler deletes orphan object state") {
        scenario("Single-value state for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object state")
            val namespace = UUID.randomUUID().toString
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Host], namespace))
            val path = store.stateObjectPath(namespace, classOf[Port], portId)
            curator.create()
                .creatingParentContainersIfNeeded()
                .forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedObjects shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should be deleted")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) shouldBe null
        }

        scenario("Multi-value state for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object state")
            val namespace = UUID.randomUUID().toString
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Host], namespace))
            val path = store.stateObjectPath(namespace, classOf[Port], portId) +
                       "/value"
            curator.create()
                .creatingParentContainersIfNeeded()
                .forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedObjects shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should be deleted")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) shouldBe null
        }

        scenario("Single-value state for existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object state")
            val namespace = UUID.randomUUID().toString
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Host], namespace))
            curator.create().forPath(store.objectPath(classOf[Port], portId))
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(store.stateObjectPath(
                       namespace, classOf[Port], portId))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedObjects shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should exist")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) should not be null
        }
    }

    feature("Recycler deletes orphan object tables") {
        scenario("Table for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val portId = UUID.randomUUID()
            val path = store.tablesObjectPath(classOf[Port], portId)
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedTables shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should be deleted")
            curator.checkExists()
                .forPath(store.tablesObjectPath(classOf[Port], portId)) shouldBe null
        }

        scenario("Table for existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Port], portId))
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(store.tablesObjectPath(classOf[Port], portId))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.deletedTables shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should exist")
            curator.checkExists()
                .forPath(store.tablesObjectPath(classOf[Port], portId)) should not be null
        }
    }

    feature("Recycler deletes orphan legacy paths") {
        scenario("Table for non-existing bridge") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val path = s"${store.rootPath}/bridges/${UUID.randomUUID()}"
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.totalLegacy shouldBe 1
            result.get.deletedLegacy shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should be deleted")
            curator.checkExists().forPath(path) shouldBe null
        }

        scenario("Table for existing bridge") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val bridgeId = UUID.randomUUID()
            val path = s"${store.rootPath}/bridges/$bridgeId"
            curator.create().forPath(store.objectPath(classOf[Network], bridgeId))
            curator.create().creatingParentContainersIfNeeded().forPath(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.totalLegacy shouldBe 1
            result.get.deletedLegacy shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should exist")
            curator.checkExists().forPath(path) should not be null
        }

        scenario("Table for non-existing router") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val path = s"${store.rootPath}/routers/${UUID.randomUUID()}"
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(path)
            waitForExpiry(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.totalLegacy shouldBe 1
            result.get.deletedLegacy shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should be deleted")
            curator.checkExists().forPath(path) shouldBe null
        }

        scenario("Table for existing router") {
            Given("A recycling service")
            val recycler = newRecycler()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            And("A node for an object table")
            val routerId = UUID.randomUUID()
            val path = s"${store.rootPath}/routers/$routerId"
            curator.create().forPath(store.objectPath(classOf[Router], routerId))
            curator.create().creatingParentContainersIfNeeded().forPath(path)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            recycler.mockedClock.time = System.currentTimeMillis() +
                                        clusterConfig.recycler.interval.toMillis

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()

            And("Recycling should not be skipped")
            result.isSuccess shouldBe true
            result.get.totalLegacy shouldBe 1
            result.get.deletedLegacy shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object table path should exist")
            curator.checkExists().forPath(path) should not be null
        }
    }
}
