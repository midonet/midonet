/*
 * Copyright (c) 2016 Midokura SARL
 */

package org.midonet.cluster.services.endpoint.comm

import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import javax.net.ssl.HostnameVerifier

import scala.async.Async.async
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.Random

import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.ssl.{SSLContextBuilder, TrustStrategy}
import org.junit.runner.RunWith
import org.mockito.{Matchers => MockMatchers}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.util.PortProvider
import org.midonet.util.netty.ServerFrontEnd

import io.netty.buffer.Unpooled
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.handler.ssl.{SslContext, SslContextBuilder}

@RunWith(classOf[JUnitRunner])
class HttpByteBufferHandlerTest extends FeatureSpec
                                        with Matchers
                                        with BeforeAndAfter {

    private val log = LoggerFactory.getLogger(this.getClass)
    private val timeout = Duration(60, TimeUnit.SECONDS)

    private var buffer: Array[Byte] = _
    private var handler: HttpByteBufferHandler = _

    before {
        buffer = new Array[Byte](4 * 1024 * 1024)
        Random.nextBytes(buffer)
        val bufferProvider = new HttpByteBufferProvider {
            private val httpBuffer = Unpooled.wrappedBuffer(buffer)
            override def getByteBuffer() = httpBuffer
        }
        handler = new HttpByteBufferHandler(bufferProvider)
    }

    private def createSelfSignedSSLContext = {
        val cert = new SelfSignedCertificate
        SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
            .build()
    }

    private def createHttpServer(sslCtx: Option[SslContext]) = {
        val port = PortProvider.getPort
        (port, ServerFrontEnd.tcp(new HTTPAdapter(sslCtx) {
            override def initChannel(ch: SocketChannel): Unit = {
                super.initChannel(ch)

                ch.pipeline.addLast(handler)
            }
        }, port))
    }

    private def createUrl(port: Int,
                          protocol: String = "http") = {
        s"$protocol://localhost:$port"
    }

    private def createHttpClient: CloseableHttpClient = {
        // Create a trusting SSL context builder
        val sslCtxBuilder = new SSLContextBuilder
        sslCtxBuilder.loadTrustMaterial(null, new TrustStrategy {
            override def isTrusted(chain: Array[X509Certificate],
                                   authType: String): Boolean = true
        })

        // Don't check hostname against certificate
        val allowAllVerifier: HostnameVerifier = NoopHostnameVerifier.INSTANCE

        HttpClients.custom
            .setSSLHostnameVerifier(allowAllVerifier)
            .setSslcontext(sslCtxBuilder.build()).build()
    }

    private def startHttpServer(sslCtx: Option[SslContext])
    : (Int, ServerFrontEnd, Option[SslContext]) = {
        val (port, server) = createHttpServer(sslCtx)
        server.startAsync().awaitRunning(timeout.toSeconds, TimeUnit.SECONDS)
        (port, server, sslCtx)
    }

    private def stopHttpServer(server: ServerFrontEnd) = {
        server.stopAsync().awaitTerminated(timeout.toSeconds, TimeUnit.SECONDS)
    }

    private def testResponseBodyOK(port: Int, sslCtx: Option[SslContext]) = {
        val httpClient = createHttpClient

        val url = createUrl(port, protocol =
            if (sslCtx.isDefined) "https" else "http")
        val result = async {
            httpClient.execute(new HttpGet(url))
        }

        val response = Await.result(result, timeout)
        response.getStatusLine.getStatusCode shouldBe OK.code()
        response.getEntity.getContentType.getValue shouldBe "application/octet-stream"

        val receivedBytes = IOUtils.toByteArray(
            response.getEntity.getContent)

        receivedBytes shouldBe buffer
    }


    feature("http byte buffer server handler") {
        scenario("non-get request not mocked") {
            val (port, server, _) = startHttpServer(None)

            val httpClient = createHttpClient
            val url = createUrl(port)
            val result = async {
                httpClient.execute(new HttpPost(url))
            }
            val response = Await.result(result, timeout)
            response.getStatusLine.getStatusCode shouldBe METHOD_NOT_ALLOWED.code()

            stopHttpServer(server)
        }

        scenario("netty test without ssl") {
            val (port, server, sslCtx) = startHttpServer(None)

            testResponseBodyOK(port, sslCtx)

            stopHttpServer(server)
        }

        scenario("netty test with ssl") {
            val (port, server, sslCtx) = startHttpServer(
                Some(createSelfSignedSSLContext))

            testResponseBodyOK(port, sslCtx)

            stopHttpServer(server)
        }

    }

}
