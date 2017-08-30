/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.data.storage.cached

import java.net.URI
import java.security.cert.X509Certificate
import java.util.concurrent.{ExecutorService, Executors, TimeUnit}

import javax.net.ssl.SSLContext

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.Random

import com.google.common.net.HostAndPort

import org.apache.http.{HttpException, NoHttpResponseException}
import org.apache.http.ssl.{SSLContextBuilder, TrustStrategy}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.services.discovery.{MidonetDiscoverySelector, MidonetServiceURI}

import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.SslContextBuilder
import io.netty.buffer.{ByteBuf, ByteBufInputStream, Unpooled}
import io.netty.channel.{ChannelHandler, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.stream.ChunkedStream
import io.netty.util.CharsetUtil
import org.midonet.util.PortProvider
import org.midonet.util.concurrent.{NamedThreadFactory, ThreadHelpers}
import org.midonet.util.netty.{HTTPAdapter, ServerFrontEnd}

import io.netty.channel.ChannelHandler.Sharable

@RunWith(classOf[JUnitRunner])
class TopologyCacheClientTest extends FeatureSpec with Matchers
                                      with BeforeAndAfter {

    private val Timeout = Duration(5, TimeUnit.SECONDS)
    private val SrvHostName = "localhost"

    @Sharable
    private class HTTPHandler(path: String, buf: ByteBuf, baseUri: String = "/")
        extends SimpleChannelInboundHandler[FullHttpRequest] {

        override def channelRead0(ctx: ChannelHandlerContext,
                                  msg: FullHttpRequest): Unit = {
            if (msg.decoderResult().isFailure) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST)
            } else if (msg.method() != HttpMethod.GET) {
                sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED)
            } else if (uriPath(msg.uri) != path) {
                sendError(ctx, HttpResponseStatus.FORBIDDEN)
            } else {
                val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                                                   HttpResponseStatus.OK)
                val headers = new CombinedHttpHeaders(true)
                headers.add(HttpHeaderNames.CACHE_CONTROL,
                            HttpHeaderValues.NO_STORE)
                headers.add(HttpHeaderNames.CACHE_CONTROL,
                            HttpHeaderValues.MUST_REVALIDATE)
                headers.add(HttpHeaderNames.CONTENT_TYPE,
                            HttpHeaderValues.APPLICATION_OCTET_STREAM)
                headers.add(HttpHeaderNames.CONTENT_LENGTH,
                            buf.readableBytes())
                resp.headers().add(headers)
                ctx.write(resp)
                ctx.writeAndFlush(new HttpChunkedInput(
                    new ChunkedStream(new ByteBufInputStream(buf))))
            }
        }

        override def exceptionCaught(ctx: ChannelHandlerContext, exc: Throwable)
        : Unit = {
            ctx.close()
        }

        private def uriPath(uriStr: String): String =
            URI.create(baseUri).resolve(uriStr).getPath

        private def sendError(ctx: ChannelHandlerContext,
                              code: HttpResponseStatus) = {
            val resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, code,
                Unpooled.copiedBuffer("Failure: " + code + "\r\n",
                                      CharsetUtil.UTF_8)
            )
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE,
                               HttpHeaderValues.TEXT_PLAIN +
                               "; charset=" + CharsetUtil.UTF_8.name())
            ctx.writeAndFlush(resp)
        }
    }

    private def createHttpServer(port: Int, handler: ChannelHandler,
                                 ssl: Boolean = false)
    : ServerFrontEnd = {
        val sslCtx = if (ssl) {
            val cert = new SelfSignedCertificate("*")
            Some(SslContextBuilder
                     .forServer(cert.certificate(), cert.privateKey())
                     .build())
        } else {
            None
        }
        ServerFrontEnd.tcp(new HTTPAdapter(sslCtx) {
            override def initChannel(ch: SocketChannel): Unit = {
                super.initChannel(ch)
                ch.pipeline().addLast(handler)
            }
        }, port)
    }

    private def clientSslCtx: SSLContext = {
        val sslBuilder = new SSLContextBuilder
        sslBuilder.loadTrustMaterial(
            new TrustStrategy {
                override def isTrusted(chain: Array[X509Certificate],
                                       authType: String): Boolean = true
            }
        ).build()
    }

    private def createURI(scheme: String, host: String, port: Int, path: String)
    : URI = new URI(scheme, null, host, port, path, null, null)

    private var thread: ExecutorService = _
    private var executor: ExecutionContext = _
    private var data: Array[Byte] = _
    private var handler: HTTPHandler = _
    private var port: Int = _
    private var server: ServerFrontEnd = _

    before {
        data = new Array[Byte](4 * 1024 * 1024)
        Random.nextBytes(data)
        val buffer = Unpooled.wrappedBuffer(data)
        handler = new HTTPHandler("/topology-cache", buffer)
        port = PortProvider.getPort
        thread = Executors.newSingleThreadExecutor(
            new NamedThreadFactory("topology-cache-client-test", false))
        executor = ExecutionContext.fromExecutor(thread)
    }

    after {
        if (server != null && server.isRunning) {
            server.stopAsync().awaitTerminated(Timeout.length, Timeout.unit)
        }
        server = null
        ThreadHelpers.terminate(thread, Timeout)
    }

    feature("Nice failures") {
        scenario("invalid path") {
            server = createHttpServer(port, handler)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val srv = HostAndPort.fromParts(SrvHostName, port)
            val client = new TopologyCacheClientImpl(srv, None) {
                override protected val url: URI =
                    createURI("http", srv.getHostText, srv.getPort, "/wrong")
            }

            val exc = the [HttpException] thrownBy
                Await.result(client.fetch()(executor), Timeout)

            exc.getMessage should be
                "Topology cache client got non-OK code: " +
                HttpResponseStatus.FORBIDDEN
        }
        scenario("mismatching ssl settings") {
            server = createHttpServer(port, handler, ssl = true)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val client = new TopologyCacheClientImpl(
                HostAndPort.fromParts(SrvHostName, port), None)

            a [NoHttpResponseException] shouldBe thrownBy(
                Await.result(client.fetch()(executor), Timeout))
        }
    }

    feature("data recovery - fixed address") {
        scenario("clear connection") {
            server = createHttpServer(port, handler)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val client = new TopologyCacheClientImpl(
                HostAndPort.fromParts(SrvHostName, port), None)

            val result = Await.result(client.fetch()(executor), Timeout)
            result shouldBe data
        }
        scenario("encrypted connection") {
            server = createHttpServer(port, handler, ssl = true)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val client = new TopologyCacheClientImpl(
                HostAndPort.fromParts(SrvHostName, port), Some(clientSslCtx))

            val result = Await.result(client.fetch()(executor), Timeout)
            result shouldBe data
        }
    }

    feature("data recovery - service discovery") {
        scenario("clear connection") {
            server = createHttpServer(port, handler)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val discovery = new MidonetDiscoverySelector[MidonetServiceURI] {
                override def getInstance = Some(MidonetServiceURI(
                    createURI("http", SrvHostName, port, "/topology-cache")
                ))
            }

            val client = new TopologyCacheClientDiscovery(discovery, None)

            val result = Await.result(client.fetch()(executor), Timeout)
            result shouldBe data
        }
        scenario("encrypted connection") {
            server = createHttpServer(port, handler, ssl = true)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val discovery = new MidonetDiscoverySelector[MidonetServiceURI] {
                override def getInstance = Some(MidonetServiceURI(
                    createURI("https", SrvHostName, port, "/topology-cache")
                ))
            }

            val client =
                new TopologyCacheClientDiscovery(discovery, Some(clientSslCtx))

            val result = Await.result(client.fetch()(executor), Timeout)
            result shouldBe data
        }
        scenario("server unavailable") {
            server = createHttpServer(port, handler)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val discovery = new MidonetDiscoverySelector[MidonetServiceURI] {
                override def getInstance: Option[MidonetServiceURI] = None
            }

            val client = new TopologyCacheClientDiscovery(discovery, None)

            val exc = the [HttpException] thrownBy
                      Await.result(client.fetch()(executor), Timeout)

            exc.getMessage shouldBe "Topology cache service unavailable"
        }
    }

    feature("data compression") {
        scenario("if de-activated, a simple request should still work") {
            server = createHttpServer(port, handler)
            server.startAsync().awaitRunning(Timeout.length, Timeout.unit)

            val client = new TopologyCacheClientImpl(
                HostAndPort.fromParts(SrvHostName, port), None)

            val result = Await.result(client.fetch(compress = false)(executor),
                                      Timeout)
            result shouldBe data
        }
    }
}
