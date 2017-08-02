/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint

import java.io.File
import java.net.InetAddress
import java.util.UUID

import scala.collection.JavaConverters.mapAsScalaMapConverter
import scala.util.{Failure, Success, Try}

import com.codahale.metrics.MetricRegistry
import com.google.common.io.Files
import com.google.inject._
import com.typesafe.config.Config

import org.apache.commons.io.FileUtils
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.mockito.Mockito
import org.reflections.Reflections
import org.slf4j.LoggerFactory

import rx.subjects.BehaviorSubject

import org.midonet.cluster.auth.{AuthService, MockAuthService}
import org.midonet.cluster.conf.ClusterConfig
import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.FakeDiscovery
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.MidoTestConfigurator
import org.midonet.minion.Context

/**
  * Utilities for all services-related tests.
  */
object ClusterTestUtils {
    private val log = LoggerFactory.getLogger(ClusterTestUtils.getClass)

    class TestBackend(backendCfg: MidonetBackendConfig,
                      zkCurator: CuratorFramework, metrics: MetricRegistry)
        extends MidonetBackend {

        val namespaceId = new UUID(0L, 0L)
        private val zoom = new ZookeeperObjectMapper(
            backendCfg, namespaceId.toString, curator, curator, null, null,
            new StorageMetrics(metrics))
        val connectionState =
            BehaviorSubject.create[ConnectionState](ConnectionState.CONNECTED)

        override def store = zoom
        override def stateStore = zoom
        override def stateTableStore = zoom
        override def curator = zkCurator
        override def failFastCurator = zkCurator
        override def reactor = null
        override def failFastConnectionState = connectionState.asObservable()
        override def stateTableClient = null
        override val discovery = new FakeDiscovery

        override def doStart(): Unit = {
            notifyStarted()
        }

        override def doStop(): Unit = {
            notifyStopped()
        }
    }

    private val authConf = Mockito.mock(classOf[Config])

    class ReflectionsModule(path: String = "org.midonet.mem") extends AbstractModule {
        lazy val reflections = new Reflections(path)
        override def configure(): Unit =
            bind(classOf[Reflections]).toInstance(reflections)
    }

    class TestModule[T](clazz: Class[T], curator: CuratorFramework,
                        confStr: String, extraModules: Module*)
        extends AbstractModule {

        val metrics = new MetricRegistry

        private val nodeCtx = Context(UUID.randomUUID())
        private val zkRoot = "/test"
        private val configurator = MidoTestConfigurator.forClusters(
            s"""
               |zookeeper.root_key=$zkRoot
               |$confStr
            """.stripMargin)
        private val backendCfg = new MidonetBackendConfig(configurator)
        private val backend = new TestBackend(backendCfg, curator, metrics)
        backend.startAsync().awaitRunning()

        override def configure(): Unit = {
            bind(classOf[Context]).toInstance(nodeCtx)
            bind(classOf[AuthService]).toInstance(new MockAuthService(authConf))
            bind(classOf[MetricRegistry]).toInstance(metrics)
            bind(classOf[MidonetBackend]).toInstance(backend)
            bind(classOf[MidonetBackendConfig]).toInstance(backendCfg)
            bind(classOf[ClusterConfig])
                .toInstance(new ClusterConfig(configurator))

            extraModules.foreach(install)

            bind(clazz).in(classOf[Singleton])
        }
    }

    def createModule[T](curator: CuratorFramework, confStr: String,
                        extraModules: Module*)(implicit clazz: Class[T])
    : TestModule[T] =
        new TestModule[T](clazz, curator, confStr, extraModules: _*)

    def createInjector(m: TestModule[_], storageClasses: Class[_]*): Injector = {
        val injector = Guice.createInjector(m)
        val store = injector.getInstance(classOf[MidonetBackend]).store
        storageClasses.foreach(store.registerClass)
        store.build()
        injector
    }

    def getMetric[M](key: String)(implicit m: TestModule[_]): Option[M] =
        m.metrics.getGauges.asScala.get(key).map(_.getValue)
            .asInstanceOf[Option[M]]

    def getMetricKeys(implicit m: TestModule[_]): Set[String] =
        m.metrics.getGauges.asScala.keySet.toSet

    def hostSelfDetectionTest(test: => Unit): Unit =
        Try(InetAddress.getLocalHost) match {
            case Success(_) =>
                test
            case Failure(_) =>
                log.warn("Skipping test since we can't find localhost.")
        }

    def withTmpDir(numFiles: Int, test: List[File] => Unit): Unit = {
        val tempDirs = (0 until numFiles).map(_ => Files.createTempDir())
        tempDirs.foreach(FileUtils.forceDeleteOnExit)
        test(tempDirs.toList)
        tempDirs.foreach(FileUtils.deleteQuietly)
    }

    def withTmpDir(test: File => Unit): Unit =
        withTmpDir(1, { tmpDirs => test(tmpDirs.head) })

    def withTmpDir(test: (File, File) => Unit): Unit =
        withTmpDir(2, { tmpDirs => test(tmpDirs.head, tmpDirs(1)) })
}
