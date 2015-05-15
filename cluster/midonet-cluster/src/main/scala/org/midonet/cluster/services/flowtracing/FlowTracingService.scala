/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.flowtracing

import java.util.Date

import com.datastax.driver.core.utils.UUIDs
import com.google.inject.Inject
import com.google.inject.name.Named
import com.google.protobuf.ByteString
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory

import org.midonet.cluster.{ClusterConfig, ClusterMinion, ClusterNode, FlowTracingConfig}
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.cluster.flowtracing.FlowTracing.{FlowTrace => FlowTraceProto, FlowTraceData => FlowTraceDataProto, _}
import org.midonet.cluster.models.Commons.{EtherType, Protocol}
import org.midonet.cluster.util.{IPAddressUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.config._
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider
import org.midonet.midolman.logging.FlowTracingSchema
import org.midonet.packets.{IPAddr, MAC}
import org.midonet.util.eventloop.Reactor
import org.midonet.util.netty.{ProtoBufWebSocketServerAdapter, ServerFrontEnd}

object FlowTracingService {
    val WebSocketPath = "/traces"
}

class FlowTracingMinion @Inject()(nodeCtx: ClusterNode.Context,
                                  clusterConfig: ClusterConfig,
                                  @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG) reactor: Reactor)
        extends ClusterMinion(nodeCtx) {
    private val log = LoggerFactory.getLogger(classOf[FlowTracingService])

    val cass = new CassandraClient(
        clusterConfig.backend,
        clusterConfig.cassandra,
        FlowTracingSchema.KEYSPACE_NAME,
        FlowTracingSchema.SCHEMA, FlowTracingSchema.SCHEMA_TABLE_NAMES)

    val service = new FlowTracingService(
        clusterConfig.flowTracing, new CassandraFlowTracingStorage(cass))

    @Override
    def doStart(): Unit = {
        try {
            service.start()
            notifyStarted()
        } catch {
            case e: Exception =>
                log.warn("Service start failed")
                notifyFailed(e)
        }
    }

    @Override
    def doStop(): Unit = {
        service.stop()
        notifyStopped()
    }
}

class FlowTracingService(cfg: FlowTracingConfig,
                         flowTracingStorage: FlowTracingStorageBackend) {
    private val log = LoggerFactory.getLogger(classOf[FlowTracingService])
    private var wsSrv: ServerFrontEnd = null

    def start(): Unit = {
        log.info("Starting the Flow Tracing Service")

        val srvHandler = new FlowTracingHandler(flowTracingStorage)

        wsSrv = ServerFrontEnd.tcp(
            new ProtoBufWebSocketServerAdapter(
                srvHandler, Request.getDefaultInstance,
                FlowTracingService.WebSocketPath),
            cfg.getPort
        )

        if (wsSrv != null) wsSrv.startAsync().awaitRunning()
        log.info("Service started")
    }

    def stop(): Unit = {
        log.info("Stopping the Flow Tracing Service")
        if (wsSrv != null) wsSrv.stopAsync().awaitTerminated()
        log.info("Service stopped")
    }
}

@Sharable
class FlowTracingHandler(storage: FlowTracingStorageBackend)
        extends SimpleChannelInboundHandler[Request] {
    private val log = LoggerFactory.getLogger(classOf[FlowTracingHandler])

    override def channelRead0(ctx: ChannelHandlerContext,
                              msg: Request): Unit = {
        val txnid = msg.getTxnid
        val resp = Response.newBuilder
        resp.setTxnid(txnid)
        try {
            if (msg.hasFlowCountRequest) {
                val req = msg.getFlowCountRequest
                resp.setFlowCountResponse(handleFlowCountRequest(req))
            } else if (msg.hasFlowTracesRequest) {
                val req = msg.getFlowTracesRequest
                resp.setFlowTracesResponse(handleFlowTracesRequest(req))
            } else if (msg.hasFlowTraceDataRequest) {
                val req = msg.getFlowTraceDataRequest
                resp.setFlowTraceDataResponse(handleFlowTraceDataRequest(req))
            }
        } catch {
            case notFound: TraceNotFoundException => {
                resp.setError(ErrorResponse.newBuilder()
                                  .setCode(ErrorCode.TRACE_NOT_FOUND).build)
            }
            case t: Throwable => {
                log.error(s"Error handling flow tracing request ${msg}", t)
                resp.setError(ErrorResponse.newBuilder()
                                  .setCode(ErrorCode.INTERNAL_ERROR).build)
            }
        }
        ctx.channel.writeAndFlush(resp.build())
        ReferenceCountUtil.release(msg)
    }

    private def dateOrNow(timestamp: Long): Date = {
        if (timestamp == 0) {
            new Date()
        } else {
            new Date(timestamp)
        }
    }

    def handleFlowCountRequest(req: FlowCountRequest): FlowCountResponse = {
        val count = storage.getFlowCount(req.getTraceRequestId,
                                         if (req.hasMaxTimestamp)
                                             new Date(req.getMaxTimestamp)
                                         else
                                             new Date(),
                                         if (req.hasLimit)
                                             req.getLimit
                                         else
                                             Int.MaxValue)
        FlowCountResponse.newBuilder()
            .setCount(count).build()
    }

    def handleFlowTracesRequest(req: FlowTracesRequest): FlowTracesResponse = {
        val flows = storage.getFlowTraces(req.getTraceRequestId,
                                          if (req.hasMaxTimestamp)
                                             new Date(req.getMaxTimestamp)
                                         else
                                             new Date(),
                                         if (req.hasLimit)
                                             req.getLimit
                                         else
                                             Int.MaxValue)
        import FlowTraceUtil._
        val builder = FlowTracesResponse.newBuilder
        var i = 0
        while (i < flows.size()) {
            builder.addTrace(flows.get(i))
            i += 1
        }
        builder.build
    }

    def handleFlowTraceDataRequest(req: FlowTraceDataRequest):
            FlowTraceDataResponse = {
        val (ftrace, data) =
            storage.getFlowTraceData(req.getTraceRequestId,
                                     req.getFlowTraceId,
                                     if (req.hasMaxTimestamp)
                                         new Date(req.getMaxTimestamp)
                                     else
                                         new Date(),
                                     if (req.hasLimit)
                                         req.getLimit
                                     else
                                         Int.MaxValue)
        import FlowTraceUtil._
        val builder = FlowTraceDataResponse.newBuilder
        builder.setTrace(ftrace)
        var i = 0
        while (i < data.size()) {
            val item = data.get(i)
            builder.addTraceData(FlowTraceDataProto.newBuilder
                                     .setHost(UUIDUtil.toProto(item.host))
                                     .setTimestamp(item.timestamp)
                                     .setData(item.data).build())
            i += 1
        }
        builder.build
    }
}

object FlowTraceUtil {
    implicit def toProto(etherType: Short): EtherType = {
        EtherType.valueOf(etherType)
    }

    implicit def toProto(trace: FlowTrace): FlowTraceProto = {
        val builder = FlowTraceProto.newBuilder
            .setId(UUIDUtil.toProto(trace.id))
            .setTimestamp(UUIDs.unixTimestamp(trace.id))

        if (trace.ethSrc != null) {
            builder.setEthSrc(ByteString.copyFrom(trace.ethSrc.getAddress))
        }
        if (trace.ethDst != null) {
            builder.setEthDst(ByteString.copyFrom(trace.ethDst.getAddress))
        }
        val etherType = EtherType.valueOf(trace.etherType)
        if (etherType != null) {
            builder.setEtherType(etherType)
        }
        if (trace.networkSrc != null) {
            builder.setNetworkSrc(IPAddressUtil.toProto(trace.networkSrc))
        }
        if (trace.networkDst != null) {
            builder.setNetworkDst(IPAddressUtil.toProto(trace.networkDst))
        }
        val proto = Protocol.valueOf(trace.networkProto)
        if (proto != null) {
            builder.setNetworkProto(proto)
        }
        builder.setSrcPort(trace.srcPort).setDstPort(trace.dstPort)

        builder.build()
    }

    implicit def fromProto(proto: FlowTraceProto): FlowTrace = {
        FlowTrace(UUIDUtil.fromProto(proto.getId),
                  if (proto.hasEthSrc)
                      MAC.fromAddress(proto.getEthSrc().toByteArray)
                  else null,
                  if (proto.hasEthDst)
                      MAC.fromAddress(proto.getEthDst().toByteArray)
                  else null,
                  if (proto.hasEtherType)
                      proto.getEtherType.getNumber.toShort
                  else 0,
                  if (proto.hasNetworkSrc)
                      IPAddressUtil.toIPAddr(proto.getNetworkSrc)
                  else null,
                  if (proto.hasNetworkDst)
                      IPAddressUtil.toIPAddr(proto.getNetworkDst)
                  else null,
                  if (proto.hasNetworkProto)
                      proto.getNetworkProto.getNumber.toByte
                  else 0,
                  proto.getSrcPort, proto.getDstPort)
    }
}
