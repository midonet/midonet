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

import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.zookeeper.data.Stat
import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import rx.Observable

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.backend.zookeeper.{ZkConnectionAwareWatcher, ZkConnection}
import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.{Port, Host}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.util.MidonetBackendTest
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.{MidonetEventually, UnixClock}
import org.midonet.util.eventloop.Reactor

@RunWith(classOf[JUnitRunner])
class RecyclerTest extends FeatureSpec with MidonetBackendTest with Matchers
                           with GivenWhenThen with MidonetEventually {

    private class ManualScheduledExecutorService
        extends SameThreadButAfterExecutorService with ScheduledExecutorService {

        private class Task(time: Long, delay: Long, unit: TimeUnit,
                              runnable: Runnable)
            extends Delayed {
            val future = new ScheduledFuture[AnyRef] {
                override def isCancelled: Boolean = canceled
                override def isDone: Boolean = done
                override def get: AnyRef = throw new InterruptedException
                override def get(timeout: Long, unit: TimeUnit): AnyRef =
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
                    runnable.run()
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

        private val tasks = new util.TreeMap[Long, util.LinkedList[Task]]

        def run(): Unit = {
            while (!tasks.isEmpty && tasks.firstKey() <= clock.time) {
                for (task <- tasks.firstEntry().getValue.asScala) {
                    task.run()
                }
                tasks.remove(tasks.firstKey())
            }
        }

        def runFirst(): Unit = {
            if (!tasks.isEmpty) {
                tasks.firstEntry().getValue.removeFirst().run()
                if (tasks.firstEntry().getValue.isEmpty) {
                    tasks.remove(tasks.firstKey())
                }
            }
        }

        def isEmpty: Boolean = tasks.isEmpty

        override def schedule(command: Runnable, delay: Long, unit: TimeUnit)
        : ScheduledFuture[_] = {
            val time = clock.time + TimeUnit.MILLISECONDS.convert(delay, unit)
            val task = new Task(time, delay, unit, command)
            if (tasks containsKey time) {
                tasks.get(time).add(task)
            } else {
                val list = new util.LinkedList[Task]()
                list.add(task)
                tasks.put(time, list)
            }
            task.future
        }

        override def schedule[V](callable: Callable[V], delay: Long,
                                 unit: TimeUnit): ScheduledFuture[V] = ???

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

    protected override def setup(): Unit = {
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        store = new ZookeeperObjectMapper(
            zkRoot, MidonetBackend.ClusterNamespaceId.toString, curator,
            curator, reactor, connection, connectionWatcher)
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
        }
        executor = new ManualScheduledExecutorService
        config = new ClusterConfig(ConfigFactory.parseString(
            """
              |cluster.recycler.enabled : true
              |cluster.recycler.interval : 1h
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
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should be skipped")
            recycler.last.get.skipped shouldBe true

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
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should be skipped")
            recycler.last.get.skipped shouldBe true

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }

        scenario("Runs recycling if schedule time is greater than NSDB timestamp") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("The NSDB timestamp")
            val stat = new Stat
            curator.getData.storingStatIn(stat).forPath(store.basePath)

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            val currentTimestamp = stat.getMtime
            clock.time = stat.getMtime + config.recycler.interval.toMillis + 1

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false

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

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = stat.getMtime + config.recycler.interval.toMillis + 1

            And("Deleting the NSDB path")
            curator.delete().deletingChildrenIfNeeded().forPath(store.basePath)

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isFailure shouldBe true

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()
        }
    }

    feature("Recycler deletes namespaces") {
        scenario("Namespaces for non-existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("A node for a namespace")
            val namespace = UUID.randomUUID().toString
            curator.create().forPath(store.stateNamespacePath(namespace))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedNamespaces.get shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(namespace)) shouldBe null
        }

        scenario("Namespaces for existing hosts") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("A node for a namespace with host")
            val id = UUID.randomUUID()
            curator.create().forPath(store.stateNamespacePath(id.toString))
            curator.create().forPath(store.objectPath(classOf[Host], id))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedNamespaces.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should not be deleted")
            curator.checkExists()
                   .forPath(store.stateNamespacePath(id.toString)) should not be null
        }

        scenario("The cluster namespace") {
            Given("A recycling service")
            val recycler = newRecycler()

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedNamespaces.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The namespace should exist")
            curator.checkExists()
                .forPath(store.stateNamespacePath(
                    MidonetBackend.ClusterNamespaceId.toString)) should not be null
        }
    }

    feature("Recycler deletes orphan object state") {
        scenario("State for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()

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
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedObjects.get shouldBe 1

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
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedObjects.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should exist")
            curator.checkExists()
                .forPath(store.stateObjectPath(
                    namespace, classOf[Port], portId)) should not be null
        }
    }


    feature("Recycler deletes orphan object tables") {
        scenario("State for non-existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("A node for an object table")
            val portId = UUID.randomUUID()
            curator.create()
                .creatingParentContainersIfNeeded()
                .forPath(store.tablesObjectPath(classOf[Port], portId))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedTables.get shouldBe 1

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should be deleted")
            curator.checkExists()
                .forPath(store.tablesObjectPath(classOf[Port], portId)) shouldBe null
        }

        scenario("State for existing objects") {
            Given("A recycling service")
            val recycler = newRecycler()

            And("A node for an object state")
            val namespace = UUID.randomUUID().toString
            val portId = UUID.randomUUID()
            curator.create().forPath(store.objectPath(classOf[Port], portId))
            curator.create()
                .creatingParentContainersIfNeeded()
                .forPath(store.tablesObjectPath(classOf[Port], portId))

            When("The recycler starts")
            recycler.startAsync().awaitRunning()
            clock.time = System.currentTimeMillis() +
                         config.recycler.interval.toMillis

            And("Executing the scheduled task")
            executor.runFirst()

            Then("The recycler should run the recycling task")
            eventually { recycler.last should not be null }
            recycler.last.isSuccess shouldBe true

            And("Recycling should not be skipped")
            recycler.last.get.skipped shouldBe false
            recycler.last.get.deletedObjects.get shouldBe 0

            And("The recycler stops")
            recycler.stopAsync().awaitTerminated()

            And("The object state path should exist")
            curator.checkExists()
                .forPath(store.tablesObjectPath(classOf[Port], portId)) should not be null
        }
    }
}
