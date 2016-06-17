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

package org.midonet.midolman.state.transfer

import java.io.{DataInputStream, IOException, OutputStream}
import java.net.Socket
import java.util.UUID

import com.typesafe.scalalogging.Logger
import org.midonet.cluster.flowstate.FlowStateTransfer.{StateRequest, StateResponse}
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.transfer.StateTransferProtocolBuilder._
import org.midonet.services.flowstate.transfer.StateTransferProtocolParser._
import org.midonet.services.flowstate.transfer.internal._
import org.slf4j.LoggerFactory.getLogger

import scala.util.control.NonFatal

class FlowStateRequestClient(socketTimeout: Int) {

    private val Log = Logger(getLogger(getClass))
    private val Retries = 3

    private var socket: Socket = _
    private var dis: DataInputStream = _
    private var host: String = _

    def remoteFlowStateFrom(host: String, tcpPort: Int, portId: UUID,
                            translator: PipelinedSbeEncoderTranslator): Unit = {
        retry {
            initSocket(tcpPort)

            val request = buildStateRequestRemote(portId, host)
            val response = sendRequest(request)

            response match {
                case StateAckRemote(port) =>
                    Log debug s"Raw Ack received from previous owner of $port"
                    pipelinedReadTranslatedState(translator)
                case StateError(code, description) =>
                    Log warn s"Ignoring response: $code error received from" +
                        s" previous owner: $description"
                case _ =>
                    Log warn "Ignoring response: received a malformed/illegal" +
                        s" response from $host"
            }
        }
    }

    def internalFlowStateFrom(tcpPort: Int, portId: UUID,
                              translator: PipelinedSbeEncoderTranslator): Unit = {
        retry {
            initSocket(tcpPort)

            val request = buildStateRequestInternal(portId)
            val response = sendRequest(request)

            response match {
                case StateAckInternal(port) =>
                    Log debug s"Internal Ack received from previous owner of $port"
                    pipelinedReadTranslatedState(translator)
                case StateError(code, description) =>
                    Log warn s"Ignoring response: $code error received from" +
                        s" previous owner: $description"
                case _ =>
                    Log warn "Ignoring response: received a malformed/illegal" +
                        s" response from $host"
            }
        }
    }

    def rawPipelinedFlowStateFrom(host: String, tcpPort: Int,
                                  portId: UUID, out: OutputStream) = {
        retry {
            initSocket(tcpPort, host)

            val request = buildStateRequestRaw(portId)
            val response = sendRequest(request)

            response match {
                case StateAckRaw(port) =>
                    Log debug s"Raw Ack received from previous owner of $port"
                    pipelinedReadWriteRawState(out)
                case StateError(code, description) =>
                    Log warn s"Ignoring response: $code error received from" +
                        s" previous owner: $description"
                case _ =>
                    Log warn "Ignoring response: received a malformed/illegal" +
                        s" response from $host"
            }
        }
    }

    private def pipelinedReadTranslatedState(translator: PipelinedSbeEncoderTranslator): Unit = {
        var next = dis.readInt()
        while (next > 0) {
            val rawEncoder = readBytes(next)
            val sbe = decodeFromBytes(rawEncoder)
            translator.pushToPipeline(sbe)
            next = dis.readInt()
        }
    }

    private def pipelinedReadWriteRawState(out: OutputStream): Unit = {
        var next = dis.readInt()
        while (next > 0) {
            val buffer = readBytes(next)
            out.write(buffer)
            next = dis.readInt()
        }
    }

    private def decodeFromBytes(bytes: Array[Byte]) = {
        val encoder = new SbeEncoder
        encoder.decodeFrom(bytes)
        encoder
    }

    private def sendRequest(stateRequest: StateRequest) = {
        try {
            socket.getOutputStream().write(stateRequest.toByteArray)

            val protobufSize = dis.readInt()

            val rawResponse = readBytes(protobufSize)
            val response = StateResponse.parseFrom(rawResponse)

            parseStateResponse(response)
        } catch {
            case e: IOException =>
                Log warn (s"Unable to get requested flow from $host", e)
                throw e
        } finally {
            socket.close()
        }
    }

    private def initSocket(tcpPort: Int, clientHost: String = "0.0.0.0"): Unit = {
        host = clientHost
        socket = new Socket(host, tcpPort)
        socket.setSoTimeout(socketTimeout)
        dis = new DataInputStream(socket.getInputStream)
    }

    private def readBytes(size: Int): Array[Byte] = {
        val buffer = new Array[Byte](size)
        dis.readFully(buffer)
        buffer
    }

    private def retry(retriable: => Unit): Unit = retry(Retries) { retriable }

    private def retry(retries: Int) (retriable: => Unit): Unit = {
        try {
            retriable
        } catch {
            case NonFatal(_) if retries > 0 => retry (retries - 1) (retriable)
            case NonFatal(_) => // Won't have the requested flow state, but we
                                // shouldn't stop
        }
    }
}
