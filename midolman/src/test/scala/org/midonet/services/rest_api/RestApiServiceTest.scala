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

package org.midonet.services.rest_api

import java.nio.file.Files
import java.nio.file.Path
import java.net.BindException
import java.util.concurrent.TimeUnit
import java.util.UUID

import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils
import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.mockito.Mockito.{mock, times, verify}
import org.mockito.{Matchers => mockito}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, GivenWhenThen, Matchers, FeatureSpec}

import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.Context

@RunWith(classOf[JUnitRunner])
class RestApiServiceTest extends FeatureSpec
    with GivenWhenThen with Matchers
    with BeforeAndAfter
    with TopologyBuilder
    with CuratorTestFramework {

    def withTempDir(test: Path => Any) {
        val dirPath = Files.createTempDirectory("test")
        try {
            test(dirPath)
        } finally {
            FileUtils.deleteDirectory(dirPath.toFile)
        }
    }

    def withConfig(test: MidolmanConfig => Any) {
        withTempDir { dirPath =>
            val socket = dirPath.resolve("test.sock")
            val config = ConfigFactory.parseString(
                s"""
                   |agent.minions.rest_api.enabled : true
                   |agent.minions.rest_api.unix_socket : ${socket}
                   |""".stripMargin)
            MidolmanConfig.forTests(config)
        }
    }

    feature("Test service lifecycle") {
        scenario("Service starts and stops") {
            withConfig { config =>
                val context = Context(UUID.randomUUID())
                val backend = mock(classOf[MidonetBackend])
                val service = new RestApiService(
                    context, backend, curator, config)
                service.startAsync().awaitRunning(60, TimeUnit.SECONDS)
                service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)
            }
        }
    }
}
