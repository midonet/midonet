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

package org.midonet.midolman.cluster

import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.RetryNTimes
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import org.slf4j.LoggerFactory

import rx.{Observable, Observer}

import org.midonet.cluster.backend.zookeeper.SessionUnawareConnectionWatcher
import org.midonet.cluster.data.storage.KeyType._
import org.midonet.cluster.data.storage.{StateResult, ZookeeperObjectMapper}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.state.RoutingTableStorage._
import org.midonet.cluster.storage.CuratorZkConnection
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.layer3.Route.NextHop
import org.midonet.util.concurrent._
import org.midonet.util.eventloop.CallingThreadReactor
import org.midonet.util.reactivex._

import ch.qos.logback.classic.Logger

@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 10)
@Fork(0)
@State(Scope.Benchmark)
class RoutingTableStorageBenchmark extends TopologyBuilder {

    private final val zkServer = "127.0.0.1:2181"
    private final val zkRoot = "/midonet/benchmark"
    private final val hostId = UUID.randomUUID()

    private val reactor = new CallingThreadReactor
    private var curator: CuratorFramework = _
    private var storage: ZookeeperObjectMapper = _

    private val random = new Random
    private val retryPolicy = new RetryNTimes(2, 1000)
    private val cnxnTimeoutMs = 2000
    private val sessionTimeoutMs = 10000
    private final val timeout = 5 seconds
    private final val benchmarkTimeout = 1800 seconds
    private final val count = 10000

    private val executor = Executors.newSingleThreadExecutor()
    private implicit val executionContext =
        ExecutionContext.fromExecutorService(executor)

    private class RoutesObserver(count: Int) extends Observer[Set[Route]] {

        private val latch = new CountDownLatch(1)

        override def onNext(routes: Set[Route]): Unit = {
            if (routes.size == count) {
                latch.countDown()
            }
        }
        override def onCompleted(): Unit = {
            latch.countDown()
        }
        override def onError(e: Throwable): Unit = {
            latch.countDown()
        }
        def await(duration: Duration): Boolean = {
            latch.await(duration.toMillis, TimeUnit.MILLISECONDS)
        }
    }

    @Setup
    def setup(): Unit = {
        System.setProperty("jute.maxbuffer", Integer.toString(40 * 1024 * 1024))
        curator = CuratorFrameworkFactory.newClient(zkServer,
                                                    sessionTimeoutMs,
                                                    cnxnTimeoutMs,
                                                    retryPolicy)
        curator.start()
        val connection = new CuratorZkConnection(curator, reactor)
        val connectionWatcher = new SessionUnawareConnectionWatcher
        connectionWatcher.setZkConnection(connection)
        storage = new ZookeeperObjectMapper(zkRoot, hostId.toString, curator,
                                            reactor, connection, connectionWatcher)
        storage.registerClass(classOf[Port])
        storage.registerKey(classOf[Port], RoutesKey, Multiple)
        storage.build()
        def root = LoggerFactory.getLogger("org.midonet").asInstanceOf[Logger]
        root.setLevel(ch.qos.logback.classic.Level.OFF)
    }

    @TearDown
    def tearDown(): Unit = {
        curator.close()
    }

    @Benchmark
    def addRoutesSync(blackhole: Blackhole): Unit = {
        val port = createRouterPort()
        storage.create(port)

        for (index <- 1 to count) {
            val route = createPortRoute(portId = port.getId)
            storage.addRoute(route).await(timeout)
        }

        storage.delete(classOf[Port], port.getId)
    }

    @Benchmark
    def addRoutesAsync(blackhole: Blackhole): Unit = {
        val port = createRouterPort()
        storage.create(port)

        val futures = new mutable.ArrayBuffer[Future[StateResult]](count)

        for (index <- 1 to count) {
            val route = createPortRoute(portId = port.getId)
            try { futures += storage.addRoute(route).asFuture }
            catch { case NonFatal(_) => }
        }

        Future.sequence(futures).await(benchmarkTimeout)

        storage.delete(classOf[Port], port.getId)
    }

    @Benchmark
    def addRemoveRoutesSync(blackhole: Blackhole): Unit = {
        val port = createRouterPort()
        storage.create(port)

        val routes = new mutable.HashSet[Route]

        for (index <- 1 to count) {
            val route = createPortRoute(portId = port.getId)

            try {
                storage.addRoute(route).await(timeout)
                routes += route
            } catch { case NonFatal(_) => }
        }

        for (route <- routes) {
            try { storage.removeRoute(route).await(timeout) }
            catch { case NonFatal(_) => }
        }

        storage.delete(classOf[Port], port.getId)
    }

    @Benchmark
    def addRemoveRoutesAsync(blackhole: Blackhole): Unit = {
        val port = createRouterPort()
        storage.create(port)

        val futuresAdd = new mutable.ArrayBuffer[Future[StateResult]](count)
        val futuresRem = new mutable.ArrayBuffer[Future[StateResult]](count)
        val routes = new mutable.HashSet[Route]

        for (index <- 1 to count) {
            val route = createPortRoute(portId = port.getId)
            try {
                futuresAdd += storage.addRoute(route).asFuture
                routes += route
            } catch { case NonFatal(_) => }
        }

        Future.sequence(futuresAdd).await(benchmarkTimeout)

        for (route <- routes) {
            try { futuresRem += storage.removeRoute(route).asFuture }
            catch { case NonFatal(_) => }
        }

        Future.sequence(futuresRem).await(benchmarkTimeout)

        storage.delete(classOf[Port], port.getId)
    }

    @Benchmark
    def addRoutesSyncAndObserver(blackhole: Blackhole): Unit = {
        val port = createRouterPort()
        storage.create(port)

        val obs = new RoutesObserver(count)
        storage.portRoutesObservable(port.getId, Observable.just(hostId))
               .subscribe(obs)

        for (index <- 1 to count) {
            val route = createPortRoute(portId = port.getId)
            try { storage.addRoute(route).await(timeout) }
            catch { case NonFatal(_) => }
        }

        obs.await(benchmarkTimeout)

        storage.delete(classOf[Port], port.getId)
    }

    private def createPortRoute(portId: UUID = UUID.randomUUID) = {
        new Route(random.nextInt(), 24, random.nextInt(), 24, NextHop.PORT,
                  portId, random.nextInt(), random.nextInt(), "",
                  UUID.randomUUID, true)
    }

}
