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

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.TimeUnit

import com.sun.jersey.guice.JerseyServletModule
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import com.typesafe.config.ConfigFactory

import org.apache.commons.io.FileUtils
import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.services.MidonetBackend
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.minion.Context
import org.midonet.netlink.{Netlink, NetlinkSelectorProvider}
import org.midonet.util.AfUnix


@RunWith(classOf[JUnitRunner])
class BindingApiServiceTest extends FeatureSpec with Matchers {

    private class BindingApiServiceForTesting(
            nodeContext: Context,
            backend: MidonetBackend,
            curator: CuratorFramework,
            config: MidolmanConfig)
        extends BindingApiService(nodeContext, backend, curator, config) {

        override protected def makeModule = {
            new JerseyServletModule {
                override protected def configureServlets(): Unit = {
                    bind(classOf[EchoHandler])
                    serve("/*").`with`(classOf[GuiceContainer])
                }
            }
        }
    }

    private def withTempDir(test: Path => Any) {
        val dirPath = Files.createTempDirectory("test")
        try {
            test(dirPath)
        } finally {
            FileUtils.deleteDirectory(dirPath.toFile)
        }
    }

    private def withConfig(test: MidolmanConfig => Any) {
        withTempDir { dirPath =>
            val socket = dirPath.resolve("test.sock")
            val config = ConfigFactory.parseString(
                s"""
                   |agent.minions.binding_api.enabled : true
                   |agent.minions.binding_api.unix_socket : $socket
                   |""".stripMargin)
            test(MidolmanConfig.forTests(config))
        }
    }

    private def createService(config: MidolmanConfig) = {
        val context = Context(UUID.randomUUID())
        val backend = mock(classOf[MidonetBackend])
        val curator = mock(classOf[CuratorFramework])
        new BindingApiServiceForTesting(context, backend, curator, config)
    }

    private def withService(test: (BindingApiService, MidolmanConfig) => Any) {
        withConfig { config =>
            val service = createService(config)
            service.startAsync().awaitRunning(60, TimeUnit.SECONDS)
            try {
                test(service, config)
            } finally {
                service.stopAsync().awaitTerminated(10, TimeUnit.SECONDS)
            }
        }
    }

    private def connect(path: String) = {
        val provider: NetlinkSelectorProvider = Netlink.selectorProvider()
        val channel = provider.openUnixDomainSocketChannel(
            AfUnix.Type.SOCK_STREAM)
        channel.connect(new AfUnix.Address(path))
        channel
    }

    private def writeString(channel: ByteChannel, data: String) =
        channel.write(ByteBuffer.wrap(data.getBytes))

    private def readString(channel: ByteChannel) = {
        val bb = ByteBuffer.allocate(1000)
        channel.read(bb)
        bb.flip
        val bytes = new Array[Byte](bb.remaining)
        bb.get(bytes)
        new String(bytes)
    }

    feature("Binding Api") {
        scenario("Start and Stop") {
            withService { (_, _) => }
        }
        scenario("PUT request") {
            withService { (service, config) =>
                val channel = connect(config.bindingApi.unixSocket)
                writeString(channel, """
                    |PUT /echo HTTP/1.1
                    |Host: dummy
                    |Content-Type: text/plain
                    |Content-Length: 6
                    |
                    |hello
                    |""".stripMargin)
                val response = readString(channel)
                response should startWith ("HTTP/1.1 200 OK")
                response should include ("Content-Type: text/plain")
                response should endWith ("hello\n")
            }
        }
    }
}
