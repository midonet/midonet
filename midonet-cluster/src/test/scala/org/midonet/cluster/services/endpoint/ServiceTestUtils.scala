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

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Awaitable}

import com.google.common.util.concurrent.Service
import com.google.inject.{Injector, Module}

import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.services.endpoint.ClusterTestUtils.TestModule
import org.midonet.minion.Minion

/**
  * Utilities for all services-related tests.
  */
object ServiceTestUtils {
    val TimeOut = Duration(5, TimeUnit.SECONDS)

    def startAndWaitRunning(service: Service, timeout: Duration = TimeOut) = {
        service.startAsync().awaitRunning(timeout.toSeconds, TimeUnit.SECONDS)
    }

    def stopAndWaitStopped(service: Service, timeout: Duration = TimeOut) = {
        service.stopAsync().awaitTerminated(timeout.toSeconds, TimeUnit.SECONDS)
    }

    def await[T](awaitable: Awaitable[T], timeout: Duration = TimeOut) = {
        Await.result(awaitable, timeout)
    }

    case class ServiceTestContext[S <: Minion](injector: Injector,
                                               module: TestModule[S],
                                               serviceClass: Class[S])

    def initTestWithStorageClasses[S <: Minion](curator: CuratorFramework,
                                                confStr: String,
                                                storageClasses: Class[_]*)
                                               (implicit serviceClass: Class[S])
        : ServiceTestContext[S] = {
        initTest(curator, confStr, Nil, storageClasses)
    }

    def initTest[S <: Minion](curator: CuratorFramework, confStr: String,
                              extraModules: Module*)
                             (implicit serviceClass: Class[S])
        : ServiceTestContext[S] = {
        initTest(curator, confStr, extraModules, Nil)
    }

    def initTest[S <: Minion](curator: CuratorFramework, confStr: String,
                              extraModules: Seq[Module],
                              storageClasses: Seq[Class[_]])
                             (implicit serviceClass: Class[S])
        : ServiceTestContext[S] = {
        val module =
            ClusterTestUtils.createModule(curator, confStr, extraModules: _*)
        val injector = ClusterTestUtils.createInjector(module,
                                                        storageClasses: _*)

        ServiceTestContext(injector, module, serviceClass)
    }

    def getService[S <: Minion](implicit context: ServiceTestContext[S]): S = {
        context.injector.getInstance(context.serviceClass)
    }

    def getMetric[M](key: String)(implicit context: ServiceTestContext[_]) = {
        ClusterTestUtils.getMetric(key)(context.module)
    }

    def getMetricKeys(implicit context: ServiceTestContext[_]) = {
        ClusterTestUtils.getMetricKeys(context.module)
    }
}
