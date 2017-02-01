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

import java.io.{BufferedWriter, ByteArrayOutputStream}
import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}

import java.net.ServerSocket
import java.util.List
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

import com.google.common.util.concurrent.AbstractService
import com.google.common.util.concurrent.ThreadFactoryBuilder

import org.midonet.midolman.logging.MidolmanLogging

object SimpleHTTPServer {
    val RestartDelay = 5 seconds
    val acceptTimeout = 500 milliseconds
    val HttpVersion = "HTTP/1.1"

    val OK = 200
    val NotFound = 404
    val BadRequest = 400
    val ServerError = 500

    trait Handler {
        def path: String
        def writeResponse(writer: BufferedWriter): Unit
    }

    class HTTPException(val code: Int) extends Exception
    class HTTPNotFoundException extends HTTPException(NotFound)
    class HTTPBadRequestException extends HTTPException(BadRequest)
}

class SimpleHTTPServer(port: Int, handlers: List[SimpleHTTPServer.Handler])
        extends MidolmanLogging {
    import SimpleHTTPServer._

    override def logSource =
        s"org.midonet.midolman.management.http-server-$port"

    val socket = new AtomicReference[ServerSocket](null)
    val content = new ByteArrayOutputStream()
    val contentWriter = new BufferedWriter(
        new OutputStreamWriter(content))

    def getPort(): Int = socket.get match {
        case null => throw new IllegalStateException("Server must be bound")
        case s => s.getLocalPort
    }

    def rebind(): Unit = {
        close()
        log.info(s"Opening HTTP server socket")
        socket.set(new ServerSocket(port))
        socket.get match {
            case null =>
            case s => log.info(
                s"HTTP server listening on port ${s.getLocalPort}")
        }
    }

    def close(): Unit = {
        socket.getAndSet(null) match {
            case null =>
            case s => {
                log.info("Closing HTTP server socket on"
                             +s" port ${s.getLocalPort}")
                s.close()
            }
        }
    }

    private def extractPath(line: String): String = {
        val parts = line.split("\\s")
        if (parts.length != 3 || parts(0) != "GET" || parts(2) != HttpVersion) {
            throw new HTTPBadRequestException
        }
        parts(1)
    }

    private def writeHeaders(output: OutputStreamWriter, code: Int,
                             contentLength: Int = 0): Unit = {
        log.debug(s"Replying with code: $code")
        output.write(HttpVersion)
        output.write(" ")
        code match {
            case OK =>
                output.write("200 OK\r\n")
                output.write(s"Content-length: $contentLength")
            case NotFound =>
                output.write("404 Not found")
            case BadRequest =>
                output.write("400 Bad request")
            case _ =>
                output.write("500 Internal server error")
        }
        output.write("\r\n\r\n")
        output.flush()
    }

    def handleRequest(): Unit = {
        val clientSocket = socket.get match {
            case null => throw new IllegalStateException("Server must be bound")
            case s => s.accept()
        }

        val input = new BufferedReader(
            new InputStreamReader(clientSocket.getInputStream()))
        val outputStream = clientSocket.getOutputStream()
        val outputWriter = new OutputStreamWriter(outputStream)
        try {
            val path = extractPath(input.readLine())
            log.debug(s"Received GET request for $path")
            var i = 0
            var found = false
            while (i < handlers.size() && !found) {
                if (handlers.get(i).path == path) {
                    // Clear the buffer
                    contentWriter.flush()
                    content.reset()

                    // Write the response to the buffer.
                    // We need to write to the buffer first
                    // so that we can get the length.
                    // Technically we could write directly to the
                    // stream, but then we wouldn't be able to
                    // communicate error conditions to the client.
                    handlers.get(i).writeResponse(contentWriter)
                    contentWriter.flush()

                    // Write the response to the socket
                    writeHeaders(outputWriter, OK, content.size())
                    content.writeTo(outputStream)
                    outputStream.flush()
                    found = true
                }
                i += 1
            }
            if (!found) {
                writeHeaders(outputWriter, NotFound)
            }
        } catch {
            case t: Throwable =>
                val code = t match {
                    case e: HTTPException => e.code
                    case _ => ServerError
                }
                writeHeaders(outputWriter, code)
        } finally {
            outputStream.close()
            input.close()
            clientSocket.close()
        }
    }
}

class SimpleHTTPServerService(port: Int,
                              handlers: List[SimpleHTTPServer.Handler])
        extends AbstractService with Runnable with MidolmanLogging {
    override def logSource =
        s"org.midonet.midolman.management.http-server-$port"

    val executor = Executors.newSingleThreadExecutor(
        new ThreadFactoryBuilder().setDaemon(true)
            .setNameFormat("simple-http").build())

    val server = new SimpleHTTPServer(port, handlers)

    override def doStart(): Unit = {
        executor.submit(this)
    }

    override def doStop(): Unit = {
        server.close()
        executor.shutdown()

    }

    private def mainLoop(): Unit = {
        do {
            server.rebind()
            try {
                do {
                    server.handleRequest()
                } while (isRunning)
            } catch {
                case t: Throwable =>
                    if (isRunning) {
                        log.error("SimpleHTTPServer threw exception", t)
                        Thread.sleep(SimpleHTTPServer.RestartDelay.toMillis)
                    }
            } finally {
                server.close()
            }
        } while (isRunning)
    }

    def run(): Unit = {
        notifyStarted()
        try {
            mainLoop
        } finally {
            notifyStopped()
        }
    }
}
