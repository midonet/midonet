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

package org.midonet.cluster.services.rest_api.neutron

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

import scala.async.Async.async
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Matchers.{any => Any}
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import rx.Observable

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.data.storage.{PersistenceOp, Storage}
import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.C3POStorageManager
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.midolman.state.PathBuilder

@RunWith(classOf[JUnitRunner])
class NeutronZoomPluginConcurrencyTest extends FeatureSpec
                                   with BeforeAndAfter
                                   with ShouldMatchers
                                   with GivenWhenThen
                                   with MockitoSugar {

    val backend: MidonetBackend = mock[MidonetBackend]
    val resContext: ResourceContext = new ResourceContext(backend, null, null,
                                                          null)
    val paths: PathBuilder = mock[PathBuilder]
    val c3po: C3POStorageManager = mock[C3POStorageManager]
    val lockFactory: ZookeeperLockFactory = mock[ZookeeperLockFactory]

    val mutex: InterProcessSemaphoreMutex =
        new InterProcessSemaphoreMutex(null, "/meh") {
            val sem = new ReentrantLock()
            override def acquire(t: Long, unit: TimeUnit): Boolean = {
                sem.lock()
                true
            }
            override def acquire(): Unit = sem.lock()
            override def release(): Unit = sem.unlock()
            override def isAcquiredInThisProcess
            : Boolean = sem.isHeldByCurrentThread
        }

    // A thread unsafe counter that will be updated when a multi is called,
    // if the plugin doesn't serialize calls, we'll get bad results.
    var threadUnsafeCounter = 0
    val store: Storage = new Storage {
        override def multi(ops: Seq[PersistenceOp]): Unit = {
            threadUnsafeCounter = threadUnsafeCounter + 1
        }
        override def isRegistered(clazz: Class[_]): Boolean = ???
        override def observable[T](clazz: Class[T], id: Any)
        : Observable[T] = ???
        override def observable[T](clazz: Class[T])
        : Observable[Observable[T]] = ???
        override def registerClass(clazz: Class[_]): Unit = ???
        override def get[T](clazz: Class[T], id: Any): Future[T] = ???
        override def exists(clazz: Class[_], id: Any): Future[Boolean] = ???
        override def getAll[T](clazz: Class[T], ids: Seq[_ <: Any])
        : Future[Seq[T]] = ???
        override def getAll[T](clazz: Class[T]): Future[Seq[T]] = ???
    }

    Mockito.when(lockFactory.createShared(Any())).thenReturn(mutex)
    Mockito.when(backend.store).thenReturn(store)

    val plugin: NeutronZoomPlugin =
        new NeutronZoomPlugin(resContext, c3po, lockFactory)

    def makePort: Port = {
        val p = new Port
        p.id = UUID.randomUUID()
        p
    }

    import org.midonet.util.concurrent._

    feature("Neutron calls are serialized") {
        scenario("Lots of concurrent calls to the backend are serialized") {
            implicit val ec = ExecutionContext.fromExecutor(
                Executors.newFixedThreadPool(10)
            )
            val f = Future.sequence(0 until 1000 map { i =>
                async { plugin.createPort(makePort) }
            })

            f.await() should have size 1000
            threadUnsafeCounter shouldBe 1000
        }
    }
}
