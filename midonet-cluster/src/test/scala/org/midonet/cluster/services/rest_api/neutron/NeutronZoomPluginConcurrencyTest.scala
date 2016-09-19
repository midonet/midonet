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

package org.midonet.cluster.services.rest_api.neutron

import java.util.{UUID, concurrent}
import java.util.concurrent.locks.ReentrantLock

import scala.async.Async.async
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex
import org.junit.runner.RunWith
import org.mockito.Matchers.{any => Any}
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar

import org.midonet.cluster.data.storage.{Storage, Transaction}
import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.C3POStorageManager
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.{RestApiConfig, ZookeeperLockFactory}
import org.midonet.midolman.state.PathBuilder

@RunWith(classOf[JUnitRunner])
class NeutronZoomPluginConcurrencyTest extends FeatureSpec
                                   with BeforeAndAfter
                                   with ShouldMatchers
                                   with GivenWhenThen
                                   with MockitoSugar {

    val backend: MidonetBackend = mock[MidonetBackend]
    val apiConfig = new RestApiConfig(ConfigFactory.parseString(s"""
            |cluster.rest_api.nsdb_lock_timeout : 30s
        """.stripMargin))
    val resContext: ResourceContext = ResourceContext(apiConfig,
                                                      backend,
                                                      executionContext = null,
                                                      lockFactory = null,
                                                      uriInfo = null,
                                                      validator = null,
                                                      seqDispenser = null)
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
    val store = Mockito.mock(classOf[Storage])
    val transaction = Mockito.mock(classOf[Transaction])

    Mockito.when(lockFactory.createShared(Any())).thenReturn(mutex)
    Mockito.when(backend.store).thenReturn(store)
    Mockito.when(store.transaction()).thenReturn(transaction)
    Mockito.when(transaction.commit()).thenAnswer(new Answer[Unit] {
        override def answer(invocation: InvocationOnMock): Unit = {
            threadUnsafeCounter = threadUnsafeCounter + 1
        }
    })

    val plugin = new NeutronZoomPlugin(resContext, c3po, lockFactory)

    def makePort: Port = {
        val p = new Port
        p.id = UUID.randomUUID()
        p
    }

    import org.midonet.util.concurrent._

    feature("Neutron calls are serialized") {
        scenario("Lots of concurrent calls to the backend are serialized") {
            implicit val ec = ExecutionContext.fromExecutor(
                concurrent.Executors.newFixedThreadPool(10)
            )
            val f = Future.sequence(0 until 1000 map { i =>
                async { plugin.createPort(makePort) }
            })

            f.await() should have size 1000
            threadUnsafeCounter shouldBe 1000
        }
    }
}
