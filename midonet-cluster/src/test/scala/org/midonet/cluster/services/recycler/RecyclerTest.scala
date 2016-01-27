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

import java.util
import java.util.UUID
import java.util.concurrent._

import scala.collection.JavaConverters._
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import rx.Observable

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{Host, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex.TestAwaitableObserver
import org.midonet.util.{MidonetEventually, UnixClock}
import org.midonet.util.eventloop.Reactor

@RunWith(classOf[JUnitRunner])
class RecyclerTest extends FeatureSpec with MidonetBackendTest with Matchers
                           with GivenWhenThen with MidonetEventually {

    private val log  = Logger(LoggerFactory.getLogger(getClass))

    private class ManualScheduledExecutorService
        extends SameThreadButAfterExecutorService with ScheduledExecutorService {

        private class Task[V](time: Long, delay: Long, unit: TimeUnit,
                              task: Callable[V])
            extends Delayed {
            val future = new ScheduledFuture[V] {
                override def isCancelled: Boolean = canceled
                override def isDone: Boolean = done
                override def get: V = throw new InterruptedException
                override def get(timeout: Long, unit: TimeUnit): V =
                    throw new InterruptedException
                override def getDelay(unit: TimeUnit): Long =
                    Task.this.unit.convert(delay, unit)
                override def cancel(mayInterruptIfRunning: Boolean): Boolean = {
                    if (!done) {
                        tasks.remove(time, this)
                        canceled = true
                        true
                    } else {
                        false
                    }
                }
                override def compareTo(d: Delayed): Int =
                    delay.compareTo(d.getDelay(unit))
            }
            private var canceled = false
            private var done = false

            def run(): Unit = {
                if (!canceled) {
                    task.call()
                    done = true
                }
            }
            override def compareTo(delayed: Delayed): Int = {
                getDelay(unit).compareTo(delayed.getDelay(unit))
            }
            override def getDelay(unit: TimeUnit): Long = {
                this.unit.convert(delay, unit)
            }
        }

        private val tasks = new util.TreeMap[Long, util.LinkedList[Task[_]]]
        private var auto = true
        private var pending = 0

        def run(): Unit = this.synchronized {
            while (!tasks.isEmpty && tasks.firstKey() <= clock.time) {
                for (task <- tasks.firstEntry().getValue.asScala) {
                    task.run()
                }
                tasks.remove(tasks.firstKey())
            }
        }

        def manual(): Unit = this.synchronized {
            auto = false
        }

        def reset(): Unit = this.synchronized {
            auto = true
        }

        def step(): Unit = this.synchronized {
            if (!tasks.isEmpty) {
                tasks.firstEntry().getValue.removeFirst().run()
                if (tasks.firstEntry().getValue.isEmpty) {
                    tasks.remove(tasks.firstKey())
                }
            } else {
                pending += 1
            }
        }

        def isEmpty: Boolean = this.synchronized { tasks.isEmpty }

        override def submit[T](task: Callable[T]): Future[T] = {
            schedule(task, 0, TimeUnit.MILLISECONDS)
        }

        override def schedule(command: Runnable, delay: Long, unit: TimeUnit)
        : ScheduledFuture[_] = {
            schedule(new Callable[AnyRef] {
                override def call(): AnyRef = {
                    command.run()
                    null
                }
            }, delay, unit)
        }

        override def schedule[V](callable: Callable[V], delay: Long,
                                 unit: TimeUnit)
        : ScheduledFuture[V] = this.synchronized {
            val time = clock.time + TimeUnit.MILLISECONDS.convert(delay, unit)
            val task = new Task[V](time, delay, unit, callable)
            if (pending > 0) {
                pending -= 1
                super.submit(callable)
            } else if (delay == 0 && auto) {
                super.submit(callable)
            } else if (tasks containsKey time) {
                tasks.get(time).add(task)
            } else {
                val list = new util.LinkedList[Task[V]]()
                list.add(task)
                tasks.put(time, list.asInstanceOf[util.LinkedList[Task[_]]])
            }
            task.future
        }

        override def scheduleAtFixedRate(command: Runnable, initialDelay: Long,
                                         period: Long, unit: TimeUnit)
        : ScheduledFuture[_] = ???

        override def scheduleWithFixedDelay(command: Runnable, initialDelay: Long,
                                            delay: Long, unit: TimeUnit)
        : ScheduledFuture[_] = ???
    }

    private val clock = UnixClock.MOCK
    private var store: ZookeeperObjectMapper = _
    private var backend: MidonetBackend = _
    private var executor: ManualScheduledExecutorService = _
    private var config: ClusterConfig = _
    private val timeout = 5 seconds

    protected override def setup(): Unit = {
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        store = new ZookeeperObjectMapper(
            zkRoot, MidonetBackend.ClusterNamespaceId.toString, curator,
            curator, stateTables, reactor)
        MidonetBackend.setupBindings(store, store)
        backend = new MidonetBackend {
            override def stateStore: StateStorage = RecyclerTest.this.store
            override def store: Storage = RecyclerTest.this.store
            override def connectionWatcher: ZkConnectionAwareWatcher = null
            override def connection: ZkConnection = null
            override def curator: CuratorFramework = RecyclerTest.this.curator
            override def failFastConnectionState: Observable[ConnectionState] = null
            override def stateTableStore: StateTableStorage = null
            override def failFastCurator: CuratorFramework = RecyclerTest.this.curator
            override def reactor: Reactor = null
            override def doStop(): Unit = { }
            override def doStart(): Unit = { }
            override def connectionState: Observable[ConnectionState] = Observable.never()
            override def stateTableClient: StateTableClient = null
        }
        executor = new ManualScheduledExecutorService
        config = new ClusterConfig(ConfigFactory.parseString(
            """
              |cluster.recycler.enabled : true
              |cluster.recycler.interval : 1h
              |cluster.recycler.throttling_rate : 1000000001
              |cluster.thread_pool_shutdown_timeout : 10s
            """.stripMargin)
        )
    }

    private def newRecycler(): Recycler = {
        new Recycler(context = null, backend, executor, config)
    }

    feature("Recycler lifecycle") {
        scenario("Service starts and stops") {
            Given("A recycling service")
            val recycler = newRecycler()

            Then("The recycler starts")
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
            clock.time = stat.getMtime + config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe false

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
            clock.time = stat.getMtime + config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe false

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }

        scenario("Runs recycling if schedule time is greater than NSDB timestamp") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            clock.time = stat.getMtime + config.recycler.interval.toMillis + 1

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            val currentTimestamp = stat.getMtime

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The NSDB timestamp should be updated")
            curator.getData.storingStatIn(stat).forPath(store.basePath)
            currentTimestamp should be < stat.getMtime
        }

        scenario("Recycle requires ZOOM backend") {
            Given("In memory storage backend")
            val s = new InMemoryStorage
            val backend = new MidonetBackend {
                override def stateStore: StateStorage = s
                override def store: Storage = s
                override def connectionWatcher: ZkConnectionAwareWatcher = null
                override def connection: ZkConnection = null
                override def curator: CuratorFramework = RecyclerTest.this.curator
                override def failFastConnectionState: Observable[ConnectionState] = null
                override def stateTableStore: StateTableStorage = null
                override def failFastCurator: CuratorFramework = RecyclerTest.this.curator
                override def reactor: Reactor = null
                override def doStop(): Unit = { }
                override def doStart(): Unit = { }
                override def connectionState: Observable[ConnectionState] = Observable.never()
                override def stateTableClient: StateTableClient = null
            }

            And("A recycling service")
            val recycler = new Recycler(context = null, backend, executor, config)

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
            clock.time = stat.getMtime + config.recycler.interval.toMillis + 1

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("Deleting the NSDB path")
            curator.delete().deletingChildrenIfNeeded().forPath(store.basePath)

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isFailure shouldBe true

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }
    }

    feature("Recycler deletes namespaces") {
        scenario("Namespaces for non-existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("A node for a namespace")
            val namespace = UUID.randomUUID().toString
            curator.create().forPath(store.stateNamespacePath(namespace))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true
            result.get.deletedNamespaces.get shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) shouldBe null
        }

        scenario("Namespaces for existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("A node for a namespace with host")
            val id = UUID.randomUUID()
            curator.create().forPath(store.stateNamespacePath(id.toString))
            curator.create().forPath(store.objectPath(classOf[Host], id))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true
            result.get.deletedNamespaces.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should not be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(id.toString)) should not be null
        }

        scenario("The cluster namespace") {
            Given("A recycling service")
            val recycler = newRecycler()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true
            result.get.deletedNamespaces.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should exist")
            curator.checkExists()
                .forPath(store.stateNamespacePath(
                    MidonetBackend.ClusterNamespaceId.toString)) should not be null
        }
    }

    feature("Recycler deletes unused objects") {
        scenario("State for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("A node for an object state")
            val namespace = UUID.randomUUID().toString
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Host], namespace))
            curator.create()
                   .creatingParentContainersIfNeeded()
                   .forPath(store.stateObjectPath(
                       namespace, classOf[Port], portId))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true
            result.get.deletedObjects.get shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should be deleted")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) shouldBe null
        }

        scenario("State for existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

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

            And("Executing the scheduled task")
            executor.step()

            Then("The recycler should run the recycling task")
            val result = recycler.tasks.toBlocking.first()
            result.isSuccess shouldBe true

            And("Recycling should not be skipped")
            result.get.completed shouldBe true
            result.get.executed shouldBe true
            result.get.deletedObjects.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should exist")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) should not be null
        }
    }

    feature("Host collector test") {
        scenario("Observable completes if canceled before collection") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Context is cancelled")
            context.cancel()

            And("The observer subscribes")
            HostCollector(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents shouldBe empty
        }

        scenario("Observable completes if canceled during collection") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            HostCollector(context).subscribe(observer)

            And("The context is canceled")
            context.cancel()

            And("Executing the next task")
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents shouldBe empty

            executor.reset()
        }

        scenario("Observable emits error if collection fails") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Deleting the hosts path")
            curator.delete()
                   .deletingChildrenIfNeeded()
                   .forPath(store.classPath(classOf[Host]))

            And("The observer subscribes")
            HostCollector(context).subscribe(observer)

            Then("The observer should receive on error")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents shouldBe empty
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents should have size 1
        }

        scenario("Observable emits empty list when no hosts") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("No hosts")
            ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper,
                                   store.classPath(classOf[Host]), false)

            When("The observer subscribes")
            HostCollector(context).subscribe(observer)

            Then("The observer should receive no hosts")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.hosts shouldBe empty
        }

        scenario("Observable emits current hosts") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Three hosts")
            val host1 = UUID.randomUUID().toString
            val host2 = UUID.randomUUID().toString
            val host3 = UUID.randomUUID().toString
            ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper,
                                   store.classPath(classOf[Host]), false)
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.objectPath(classOf[Host], host1))
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.objectPath(classOf[Host], host2))
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.objectPath(classOf[Host], host3))

            When("The observer subscribes")
            HostCollector(context).subscribe(observer)

            Then("The observer should receive no hosts")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.hosts should contain allOf(host1, host2, host3)
        }
    }

    feature("Namespace collector test") {
        scenario("Observable completes if canceled before collection") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Context is cancelled")
            context.cancel()

            And("The observer subscribes")
            NamespaceCollector(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents shouldBe empty
        }

        scenario("Observable completes if canceled during collection") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceCollector(context).subscribe(observer)

            And("The context is canceled")
            context.cancel()

            And("Executing the next task")
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents shouldBe empty

            executor.reset()
        }

        scenario("Observable emits error if collection fails") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Deleting the hosts path")
            curator.delete()
                   .deletingChildrenIfNeeded()
                   .forPath(store.statePath())

            And("The observer subscribes")
            NamespaceCollector(context).subscribe(observer)

            Then("The observer should receive on error")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents shouldBe empty
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents should have size 1
        }

        scenario("Observable emits empty list when no namespaces") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("No hosts")
            ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper,
                                   store.statePath(), false)

            When("The observer subscribes")
            NamespaceCollector(context).subscribe(observer)

            Then("The observer should receive no hosts")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.namespaces shouldBe empty
        }

        scenario("Observable emits current namespaces") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Three hosts")
            val namespace1 = UUID.randomUUID().toString
            val namespace2 = UUID.randomUUID().toString
            val namespace3 = UUID.randomUUID().toString
            ZKPaths.deleteChildren(curator.getZookeeperClient.getZooKeeper,
                                   store.statePath(), false)
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace1))
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace2))
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace3))

            When("The observer subscribes")
            NamespaceCollector(context).subscribe(observer)

            Then("The observer should receive no hosts")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.namespaces should contain allOf(namespace1, namespace2, namespace3)
        }
    }

    feature("Namespace recycler test") {
        scenario("Observable completes if canceled before recycling") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("Context is cancelled")
            context.cancel()

            And("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents shouldBe empty
            observer.getOnErrorEvents shouldBe empty
        }

        scenario("Observable completes if canceled before verification") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A namespace to recycler")
            context.namespaces = Set(UUID.randomUUID().toString)
            context.hosts = Set.empty

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            And("The context is canceled")
            context.cancel()

            And("Executing the next task")
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 0

            executor.reset()
        }

        scenario("Observable completes if canceled before deletion") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace))
            curator.getData.storingStatIn(stat)
                   .forPath(store.stateNamespacePath(namespace))
            context.timestamp = stat.getCtime + 1

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            And("Executing the next task")
            executor.step()

            And("The context is canceled")
            context.cancel()

            And("Executing the next task")
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 0

            executor.reset()
        }

        scenario("Verification fails if namespace does not exist") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 0
            context.failedNamespaces.get shouldBe 1
        }

        scenario("Verification fails if context older than namespace") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace))
            curator.getData.storingStatIn(stat)
                   .forPath(store.stateNamespacePath(namespace))
            context.timestamp = stat.getCtime

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 0
            context.failedNamespaces.get shouldBe 0
        }

        scenario("Deletion succeeds if namespace is verified") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty
            curator.create().creatingParentsIfNeeded()
                   .forPath(store.stateNamespacePath(namespace))
            curator.getData.storingStatIn(stat)
                   .forPath(store.stateNamespacePath(namespace))
            context.timestamp = stat.getCtime + 1

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 1

            And("The namespace should be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) shouldBe null
        }

        scenario("Deletion should be throttled") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty
            curator.create().creatingParentsIfNeeded()
                .forPath(store.stateNamespacePath(namespace))
            curator.getData.storingStatIn(stat)
                .forPath(store.stateNamespacePath(namespace))
            context.timestamp = stat.getCtime + 1

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            And("Executing the next task")
            executor.step()

            Then("The namespace should exist")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) should not be null

            And("Executing the next tasks")
            executor.step()
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.deletedNamespaces.get shouldBe 1

            And("The namespace should be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) shouldBe null

            executor.reset()
        }

        scenario("Deletion fails") {
            Given("A recycling context")
            val context = new RecyclingContext(config.recycler, curator, store,
                                               executor, clock, log,
                                               0 seconds)
            executor.manual()

            And("A namespace to recycler")
            val namespace = UUID.randomUUID().toString
            val stat = new Stat()
            context.namespaces = Set(namespace)
            context.hosts = Set.empty
            curator.create().creatingParentsIfNeeded()
                .forPath(store.stateNamespacePath(namespace))
            curator.getData.storingStatIn(stat)
                .forPath(store.stateNamespacePath(namespace))
            context.timestamp = stat.getCtime + 1

            And("A test observer")
            val observer = new TestAwaitableObserver[RecyclingContext]

            When("The observer subscribes")
            NamespaceRecycler(context).subscribe(observer)

            And("Executing the next task")
            executor.step()

            And("Deleting the namespace")
            curator.delete().forPath(store.stateNamespacePath(namespace))

            And("Executing the next tasks")
            executor.step()
            executor.step()

            Then("The observer should receive on completed")
            observer.awaitCompletion(timeout)
            observer.getOnCompletedEvents should have size 1
            observer.getOnNextEvents should have size 1
            observer.getOnErrorEvents shouldBe empty
            context.failedNamespaces.get shouldBe 1

            executor.reset()
        }
    }
}
