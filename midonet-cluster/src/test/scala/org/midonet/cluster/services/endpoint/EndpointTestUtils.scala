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
import java.net.{Inet4Address, NetworkInterface, URI}
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

import javax.net.ssl.HostnameVerifier

import scala.async.Async.async
import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}

import com.google.inject.Module
import com.google.protobuf.{Message, MessageLite}

import org.apache.commons.io.{FileUtils, FilenameUtils}
import org.apache.curator.framework.CuratorFramework
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.conn.routing.HttpRoute
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.impl.client.{CloseableHttpClient, HttpClients}
import org.apache.http.impl.conn.BasicHttpClientConnectionManager
import org.apache.http.protocol.HttpRequestExecutor
import org.apache.http.ssl.{SSLContextBuilder, TrustStrategy}
import org.apache.http.{HttpHost, HttpResponse, HttpVersion}
import org.slf4j.LoggerFactory

import org.midonet.cluster.conf.EndpointConfig.SSLSource
import org.midonet.cluster.conf.EndpointConfig.SSLSource.SSLSource
import org.midonet.cluster.services.endpoint.comm.{CloseOnExceptionHandler, ConnectionHandler}
import org.midonet.cluster.services.endpoint.conf.ConfigGenerator
import org.midonet.cluster.services.endpoint.users.{EndpointUser, ProtoBufWSApiEndpointUser, StaticFilesEndpointUser}
import org.midonet.minion.Minion
import org.midonet.util.PortProvider.getPort

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ByteBufInputStream, ByteBufOutputStream}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.HttpResponseStatus.OK
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE
import io.netty.handler.codec.http.websocketx.{BinaryWebSocketFrame, WebSocketClientProtocolHandler, WebSocketVersion}
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory


object EndpointTestUtils {
    lazy val reflectionsModule = new ClusterTestUtils.ReflectionsModule(
        "org.midonet.mem.cluster.services")

    def initTest(curator: CuratorFramework, confStr: String,
                 extraModules: Module*) = {
        val extra = reflectionsModule +: extraModules
        ServiceTestUtils.initTest(
            curator, confStr, extra: _*)(classOf[EndpointService])
    }

    def genConfString(enabled: Boolean = true, port: Int = getPort,
                      ssl: SSLConfig = SSLConfig(), name: String = "endpoint",
                      host: String = "", interface: String = ""): String = {
            ConfigGenerator.Endpoint.genConfString(
                enabled = enabled,
                serviceHost = host,
                servicePort = port,
                serviceInterface = interface,
                authSslEnabled = ssl.enabled,
                sslSource = ssl.source.toString,
                keystoreLocation = ssl.keystorePath,
                keystorePassword = ssl.keystorePassword,
                certificateLocation = ssl.certificatePath,
                privateKeyLocation = ssl.privateKeyPath,
                privateKeyPassword = ssl.privateKeyPassword
            )
    }

    def extractResourceToTemporaryFile(rsrcPath: String, dstDir: File) = {
        val resourceUrl = getClass.getResource(rsrcPath)
        val destFile = new File(dstDir, FilenameUtils.getName(rsrcPath))
        FileUtils.copyURLToFile(resourceUrl, destFile)
        destFile.deleteOnExit()
        destFile
    }

    /**
      * Constructs the URL for the endpoint tests.
      *
      * Basically, forces the host to localhost and allows customization of all
      * other parts.
      *
      * @param protocol Protocol of the URL
      * @param port Port of the URL
      * @param path Path of the URL
      * @return Constructed url.
      */
    def constructEndpointUrl(protocol: String = "https", port: Int = 443,
                             path: String = "") = {
        s"$protocol://localhost:$port$path"
    }

    /**
      * Create the default http client.
      *
      * We set it to accept any certificates so that it doesn't error out when
      * looking at the self-signed certificates used by the endpoint.
      *
      * @return Instance of the default http client.
      */
    def httpClient: CloseableHttpClient = {
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

    /**
      * Sends an HTTP GET message to the provided URL.
      *
      * @param url Url to send the message to.
      * @return Http response
      */
    def sendHttpGet(url: String): Future[HttpResponse] = async {
        httpClient.execute(new HttpGet(url))
    }

    /**
      * Sends the provided protobuf message through a websocket channel to the
      * provided URL and waits for its echo.
      *
      * @param url URL to send the message to
      * @param sendMsg Protobuf message to send
      * @return Future resolved when we get the echo or the channel is closed.
      */
    def sendProtoBufWSMessage(url: String, sendMsg: MessageLite)
        : Future[Option[MessageLite]] = {
        val promise = Promise[Option[MessageLite]]
        val future = promise.future

        val uri = new URI(url)
        val scheme = uri.getScheme
        val host = uri.getHost
        val port = uri.getPort

        val sslCtx = if (scheme == "wss") {
            Some(SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE).build())
        } else {
            None
        }

        val wsProtocolHandler = new WebSocketClientProtocolHandler(
            uri, WebSocketVersion.V13, null, true,
            EmptyHttpHeaders.INSTANCE, 65535, true)

        val group = new NioEventLoopGroup
        val bootstrap = new Bootstrap

        bootstrap.group(group)
            .channel(classOf[NioSocketChannel])
            .handler(new ChannelInitializer[SocketChannel] {
                override def initChannel(ch: SocketChannel) = {
                    val pipeline = ch.pipeline

                    sslCtx.foreach(ssl => {
                        pipeline.addLast(ssl.newHandler(ch.alloc()))
                    })

                    pipeline.addLast(new HttpClientCodec())
                    pipeline.addLast(new HttpObjectAggregator(65535))
                    pipeline.addLast(wsProtocolHandler)
                    pipeline.addLast(new TestWSHandler(sendMsg, promise))
                    pipeline.addLast(new CloseOnExceptionHandler)
                }
            })

        try {
            // Connect. TestWSHandler will send the message on successful
            // handshake
            val channelFuture = bootstrap.connect(host, port)

            // When future is completed close everything
            future.onComplete(_ => {
                channelFuture.channel.close()
                group.shutdownGracefully()
            })
        } catch {
            case e: Throwable => promise.tryFailure(e)
        }

        future
    }

    /**
      * Test handler that simply answers every HTTP with a 200 OK and
      * closes the connection.
      */
    @Sharable
    class TestHTTPEndpointHandler
        extends SimpleChannelInboundHandler[HttpRequest] {

        override def channelRead0(ctx: ChannelHandlerContext,
                                  msg: HttpRequest): Unit = {
            ctx.writeAndFlush(new DefaultHttpResponse(HTTP_1_1, OK))
                .addListener(ChannelFutureListener.CLOSE)
        }
    }

    /**
      * Test netty client websocket handler implementation that sends a protobuf
      * message to a server and satisfies the passed promise upon receiving a
      * protobuf response.
      *
      * @param msgToSend Message to send to the server on connect.
      * @param promise Promise that will be satisfied upon receival of protobuf
      *                message from the server.
      */
    class TestWSHandler(msgToSend: MessageLite,
                        promise: Promise[Option[MessageLite]])
        extends SimpleChannelInboundHandler[BinaryWebSocketFrame] {

        override def channelRead0(ctx: ChannelHandlerContext,
                                  msg: BinaryWebSocketFrame) = {
            try {
                val inputStream = new ByteBufInputStream(msg.content())
                val parser = msgToSend.getParserForType
                promise.success(Option(parser.parseDelimitedFrom(inputStream)))
            } catch {
                case e: Exception =>
                    promise.tryFailure(e)
            } finally {
                ctx.close()
            }
        }

        override def userEventTriggered(ctx: ChannelHandlerContext,
                                        evt: Any) = {
            try {
                // When handshake is complete, send message
                if (evt == HANDSHAKE_COMPLETE) {
                    val msg = new BinaryWebSocketFrame
                    val outputStream = new ByteBufOutputStream(msg.content)

                    msgToSend.writeDelimitedTo(outputStream)

                    ctx.writeAndFlush(msg)
                }
            } catch {
                case e: Exception =>
                    promise.tryFailure(e)
            }

            super.userEventTriggered(ctx, evt)
        }

        override def channelInactive(ctx: ChannelHandlerContext) = {
            promise.trySuccess(None)
            super.channelInactive(ctx)
        }


        override def exceptionCaught(ctx: ChannelHandlerContext,
                                     cause: Throwable) = {
            promise.tryFailure(cause)
            super.exceptionCaught(ctx, cause)
        }
    }

    /**
      * Test minion that doesn't execute anything.
      */
    abstract class TestMinion extends Minion(null) {
        val log = LoggerFactory.getLogger(s"ENDPOINT MINION")
        override def doStop(): Unit = {
            log.debug("stop test minion")
            notifyStopped()
        }

        override def doStart(): Unit = {
            log.debug("start test minion")
            notifyStarted()
        }

        def initEndpointChannel(path: String, channel: Channel) = {
            log.debug(s"init endpoint channel $path")
        }
    }

    /**
      * Basic functionality for all test endpoint service users.
      */
    trait TestEndpointUser extends TestMinion with EndpointUser {
        def testHandler: Option[SimpleChannelInboundHandler[_]]

        override def initEndpointChannel(path: String, channel: Channel) = {
            super.initEndpointChannel(path, channel)
            if (testHandler.isDefined) {
                channel.pipeline.addLast(testHandler.get)
            }
        }
    }

    object BasicTestEndpointUser {
        def apply(userName: Option[String] = None, prot: String = "http",
                  path: String = "/path", enabled: Boolean = true,
                  handler: Option[SimpleChannelInboundHandler[_]] = None) = {
            new TestMinion with TestEndpointUser {
                override val name = userName
                override def protocol(sslEnabled: Boolean) = prot
                override val endpointPath = path
                override val isEnabled = enabled
                override val testHandler = handler
            }
        }
    }

    object ApiTestEndpointServiceUser {
        def apply[Req <: Message, Res <: Message]
        (prototype: Req,
         cnxHandler: ConnectionHandler[Req, Res],
         userName: Option[String] = None,
         path: String = "/path",
         enabled: Boolean = true,
         handler: Option[SimpleChannelInboundHandler[_]] = None) = {
            new TestMinion with ProtoBufWSApiEndpointUser[Req, Res]
                with TestEndpointUser {
                override val name = userName
                override val reqPrototype = prototype
                override val apiConnectionHandler = cnxHandler
                override val endpointPath = path
                override val isEnabled = enabled
                override val testHandler = handler
            }
        }
    }

    object StaticFilesTestEndpointUser {
        def apply(filesDir: String,
                  userName: Option[String] = None,
                  path: String = "/path",
                  enabled: Boolean = true,
                  idleTimeoutMs: Option[Int] = None,
                  onCloseListener: Option[ChannelHandler] = None
                 ) = {
            new TestMinion with StaticFilesEndpointUser with TestEndpointUser {
                override val name = userName
                override val staticFilesDir = filesDir
                override val endpointPath = path
                override val isEnabled = enabled
                override val testHandler = None

                override protected def idleTimeout: Int =
                    idleTimeoutMs.getOrElse(super.idleTimeout)

                override def initEndpointChannel(path: String, channel: Channel)
                                                                    : Unit = {
                    super.initEndpointChannel(path, channel)
                    onCloseListener.foreach(channel.pipeline.addLast(_))
                }
            }
        }
    }

    class SSLConfig(val enabled: Boolean, val source: SSLSource,
                    val keystorePath: String, val keystorePassword: String,
                    val certificatePath: String, val privateKeyPath: String,
                    val privateKeyPassword: String)

    object SSLConfig {
        def apply(enabled: Boolean = true,
                  source: SSLSource = SSLSource.AutoSigned,
                  keystorePath: String = "",
                  keystorePassword: String = "",
                  certificatePath: String = "",
                  privateKeyPath: String = "",
                  privateKeyPassword: String = "") = {
            new SSLConfig(enabled, source, keystorePath, keystorePassword,
                          certificatePath, privateKeyPath,
                          privateKeyPassword)
        }
    }

    // A class that performs multiple get requests with a single connection
    // Made for testing Keep-Alive connections
    class SingleChannelHttpClient(hostName:String, port: Int) {
        private val keepAliveMs = 100000

        private val basicConnManager = new BasicHttpClientConnectionManager()
        private val context = HttpClientContext.create()
        private val host = new HttpHost(hostName, port)
        private val route = new HttpRoute(host)

        val connRequest = basicConnManager.requestConnection(route, null);
        val conn = connRequest.get(1000, TimeUnit.SECONDS);
        basicConnManager.connect(conn, route, 10000, context);
        basicConnManager.routeComplete(conn, route, context);
        context.setTargetHost(host)
        val exeRequest = new HttpRequestExecutor()

        def get(path:String, extraHeaders: Map[String,String] = Map.empty)
        : HttpResponse = {
            val get = new HttpGet(path)
            get.setProtocolVersion(HttpVersion.HTTP_1_1)
            get.setHeader(HttpHeaderNames.ACCEPT.toString, "*/*")
            get.setHeader(HttpHeaderNames.HOST.toString, hostName+":"+port)

            extraHeaders.foreach(entry => get.addHeader(entry._1, entry._2))
            val response = exeRequest.execute(get, conn, context)

            response
        }

        def close = basicConnManager.releaseConnection(conn, null, keepAliveMs, TimeUnit.SECONDS)
    }

    /**
      * Get one of the local IPs of the host we're running under.
      */
    def getAnyNonLoopbackHostIP() = {
        val interfaces = NetworkInterface.getNetworkInterfaces.asScala.toStream

        interfaces
            .filter(i => i.isUp && !i.isLoopback)
            .flatMap(_.getInetAddresses.asScala)
            .filter(_.isInstanceOf[Inet4Address])
            .map(_.getHostAddress)
            .headOption
    }
}
