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
package org.midonet.midolman.management

import java.io.BufferedWriter
import java.net.Socket

import scala.concurrent.duration._
import scala.util.Random

import com.google.common.collect.Lists

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.concurrent.Eventually._

import com.sun.jersey.api.client.{Client, ClientResponse, WebResource}

import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class SimpleHTTPServerTest extends FeatureSpec with Matchers {

    val log = Logger(LoggerFactory.getLogger(classOf[SimpleHTTPServerTest]))

    object FakeHandler extends SimpleHTTPServer.Handler {
        val Contents = "foobar"
        override val path = "/foobar"
        override def writeResponse(writer: BufferedWriter): Unit = {
            writer.append(Contents)
        }
    }

    object CrashingHandler extends SimpleHTTPServer.Handler {
        override val path = "/crash"
        override def writeResponse(writer: BufferedWriter): Unit = {
            throw new RuntimeException("Broken handler")
        }
    }

    object LargePayloadHandler extends SimpleHTTPServer.Handler {
        override val path = "/large"
        val payload = Random.alphanumeric.take(10*1000*1000).mkString
        override def writeResponse(writer: BufferedWriter): Unit = {
            writer.append(payload)
        }
    }


    feature("HTTP server service") {
        scenario("Server starts serving requests") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(FakeHandler))
            service.startAsync()
            eventually { service.awaitRunning }
            val client = Client.create()
            val webResource = client.resource(
                s"http://localhost:${service.server.getPort}/foobar")

            val response = webResource.get(classOf[ClientResponse])
            response.getStatus shouldBe 200
            response.getEntity(classOf[String]) shouldBe FakeHandler.Contents

            service.stopAsync

            eventually {
                service.awaitTerminated
            }
        }

        scenario("Server returns 404 on missing endpoint") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(FakeHandler))
            service.startAsync()
            eventually { service.awaitRunning }

            val client = Client.create()
            val webResource = client.resource(
                s"http://localhost:${service.server.getPort}/")

            val response = webResource.get(classOf[ClientResponse])
            response.getStatus shouldBe 404

            service.stopAsync

            eventually {
                service.awaitTerminated
            }
        }

        scenario("Server returns 500 on crashing handler, continues to work") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(FakeHandler, CrashingHandler))
            service.startAsync()
            eventually { service.awaitRunning }

            val client = Client.create()
            val webResource = client.resource(
                s"http://localhost:${service.server.getPort}/crash")

            val response = webResource.get(classOf[ClientResponse])
            response.getStatus shouldBe 500

            val webResource2 = client.resource(
                s"http://localhost:${service.server.getPort}/foobar")

            val response2 = webResource2.get(classOf[ClientResponse])
            response2.getStatus shouldBe 200

            service.stopAsync

            eventually {
                service.awaitTerminated
            }
        }

        scenario("Server returns 400 in invalid request") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(FakeHandler))
            service.startAsync()
            eventually { service.awaitRunning }

            val client = Client.create()
            val webResource = client.resource(
                s"http://localhost:${service.server.getPort}/foobar")

            val response = webResource.head()
            response.getStatus shouldBe 400

            service.stopAsync

            eventually {
                service.awaitTerminated
            }
        }

        scenario("Server returns a large payload correctly") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(LargePayloadHandler))
            service.startAsync()
            eventually { service.awaitRunning }
            val client = Client.create()
            val webResource = client.resource(
                s"http://localhost:${service.server.getPort}/large")

            val response = webResource.get(classOf[ClientResponse])
            response.getStatus shouldBe 200
            val payload = response.getEntity(classOf[String])
            payload shouldBe LargePayloadHandler.payload

            service.stopAsync

            eventually {
                service.awaitTerminated
            }
        }

        scenario("Server should timeout hung client") {
            val service = new SimpleHTTPServerService(
                0, Lists.newArrayList(FakeHandler))
            service.startAsync()
            eventually { service.awaitRunning }

            val hangingSocket = new Socket("localhost", service.server.getPort)

            eventually {
                val client = Client.create()
                val webResource = client.resource(
                    s"http://localhost:${service.server.getPort}/foobar")
                val response = webResource.get(classOf[ClientResponse])
                response.getStatus shouldBe 200
            }

            service.stopAsync
            eventually {
                service.awaitTerminated
            }
        }
    }
}

