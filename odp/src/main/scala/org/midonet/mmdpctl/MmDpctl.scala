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

package org.midonet.mmdpctl

import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.{TimeUnit, TimeoutException}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import org.midonet.ErrorCode
import org.midonet.util.reactivex.AwaitableObserver
import org.rogach.scallop._
import rx.Observer

import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NanoClock

class DpCtx(channel: NetlinkChannel, bufSize: Int, timeout: Duration) {

    val families = OvsNetlinkFamilies.discover(channel)
    val proto = new OvsProtocol(channel.getLocalAddress.getPid, families)
    val writer = new NetlinkBlockingWriter(channel)
    val reader = new NetlinkTimeoutReader(channel, timeout)
    val buf = BytesUtil.instance.allocateDirect(bufSize)

    val broker = new NetlinkRequestBroker(
        writer, reader, 1, buf.capacity(), buf, NanoClock.DEFAULT, timeout)

    private def writeRead[T](f: ByteBuffer => T): T = {
        writer.write(buf)
        buf.clear()
        reader.read(buf)
        buf.position(NetlinkMessage.GENL_HEADER_SIZE)
        buf.limit(buf.getInt(NetlinkMessage.NLMSG_LEN_OFFSET))
        val deserialized = f(buf)
        buf.clear()
        deserialized
    }

    class EnumObserver[T](val buf: ListBuffer[T], f: ByteBuffer => T)
        extends Observer[ByteBuffer] {
        var exception: Throwable = _
        override def onCompleted(): Unit = { }
        override def onNext(t: ByteBuffer): Unit = buf += f(t)
        override def onError(e: Throwable): Unit = exception = e
    }

    private def enumRequest[T](f: ByteBuffer => T)(prepare: ByteBuffer => Unit): List[T] = {
        val ts = new ListBuffer[T]()
        val obs = new EnumObserver[T](ts, f) with AwaitableObserver[ByteBuffer]
        val seq = broker.nextSequence()
        prepare(broker.get(seq))
        broker.publishRequest(seq, obs)
        broker.writePublishedRequests()
        while (!obs.isCompleted)
            broker.readReply()
        if (obs.exception ne null)
            throw obs.exception
        ts.toList
    }

    private object isMissing {
        def unapply(ex: NetlinkException) =
            ex.getErrorCodeEnum match {
                case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO =>
                    Some(ex)
                case _ =>
                    None
            }
    }

    def getDatapath(name: String): Datapath = {
        proto.prepareDatapathGet(0, name, buf)
        writeRead(Datapath.deserializer.deserializeFrom)
    }

    def createDatapath(name: String): Datapath = {
        proto.prepareDatapathCreate(name, buf)
        writeRead(Datapath.deserializer.deserializeFrom)
    }

    def deleteDatapath(name: String): Unit =
        try {
            proto.prepareDatapathDel(0, name, buf)
            writeRead(Datapath.deserializer.deserializeFrom)
        } catch { case isMissing(_) => }

    def listDatapaths(): List[Datapath] =
        enumRequest(Datapath.deserializer.deserializeFrom) {
            proto.prepareDatapathEnumerate
        }

    def getOrCreateDpPort(dp: Datapath, portName: String): DpPort = {
        val port =
            try {
                getDpPort(dp, portName)
            } catch { case isMissing(_) =>
                buf.clear()
                createDpPort(dp, portName)
            }
        port
    }

    def getDpPort(dp: Datapath, portName: String): DpPort = {
        proto.prepareDpPortGet(dp.getIndex, null, portName, buf)
        writeRead[DpPort](DpPort.deserializer.deserializeFrom)
    }

    def createDpPort(dp: Datapath, portName: String): DpPort = {
        val port = new NetDevPort(portName)
        proto.prepareDpPortCreate(dp.getIndex, port, buf)
        writeRead[DpPort](DpPort.deserializer.deserializeFrom)
    }

    def deleteDpPort(dp: Datapath, portName: String): Unit =
        try {
            val port = getDpPort(dp, portName)
            proto.prepareDpPortDelete(dp.getIndex, port, buf)
            writeRead(identity)
        } catch { case isMissing(_) => }

    def listDpPorts(dp: Datapath): List[DpPort] =
        enumRequest(DpPort.deserializer.deserializeFrom) {
            proto.prepareDpPortEnum(dp.getIndex, _)
        }

    def createFlow(
            dp: Datapath,
            fmatch: FlowMatch,
            actions: java.util.List[FlowAction],
            mask: FlowMask = null): Unit = {
        proto.prepareFlowCreate(
            dp.getIndex, fmatch.getKeys, actions, mask, buf, NLFlag.ACK)
        writeRead(identity)
        buf.clear()
    }

    def deleteFlow(dp: Datapath, fmatch: FlowMatch): Unit = {
        try {
            proto.prepareFlowDelete(dp.getIndex, fmatch.getKeys, buf)
            writeRead(Flow.buildFrom)
        } catch { case isMissing(_) => }
        buf.clear()
    }

    def dumpDatapath(dp: Datapath): List[Flow] =
        enumRequest(Flow.deserializer.deserializeFrom) {
            proto.prepareFlowEnum(dp.getIndex, _)
        }
}

trait DpCommand {
    def run(ctx: DpCtx): Unit
}

object FlowsCtl extends Subcommand("flows") with DpCommand {
    private val NO_MAC = MAC.fromAddress(Array[Byte](0, 0, 0, 0, 0, 0))
    private val NO_IP = IPv4Addr.fromInt(0)

    implicit val parseIp = new ValueConverter[IPv4Addr] {
        def parse(s: List[(String, List[String])]): Either[String,Option[IPv4Addr]] =
            s match {
                case (_, ip :: Nil) :: Nil => Right(Try(IPv4Addr(ip)).toOption)
                case Nil => Right(None)
                case _ => Left("IP incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[IPv4Addr]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    implicit val parseProto = new ValueConverter[IpProtocol] {
        def parse(s: List[(String, List[String])]): Either[String,Option[IpProtocol]] =
            s match {
                case (_, protocol :: Nil) :: Nil =>
                    Right(Try(IpProtocol.valueOf(protocol.toUpperCase)).toOption)
                case Nil => Right(None)
                case _ => Left("IP protocol incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[IpProtocol]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    implicit val parseMac = new ValueConverter[MAC] {
        def parse(s: List[(String, List[String])]): Either[String,Option[MAC]] =
            s match {
                case (_, mac :: Nil) :: Nil =>
                    Right(Try(MAC.fromString(mac)).toOption)
                case Nil => Right(None)
                case _ => Left("IP protocol incorrectly specified")
            }

        val tag = scala.reflect.runtime.universe.typeTag[MAC]
        val argType = org.rogach.scallop.ArgType.SINGLE
    }

    descr("flow operations (create, delete)")

    val dp = opt[String](
        "datapath",
        descr = "the datapath where to create/delete a flow",
        required = true)
    val inPort = opt[String](
        "input",
        short = 'i',
        descr = "input interface (adds it to datapath)",
        required = true)
    val outPort = opt[String](
        "output",
        short = 'o',
        descr = "output interface (adds it to datapath)",
        required = true)
    val srcMac = opt[MAC](
        "src-mac",
        noshort = true,
        descr = "source MAC address")
    val dstMac = opt[MAC](
        "dst-mac",
        noshort = true,
        descr = "destination MAC address")
    val srcIp = opt[IPv4Addr](
        "src-ip",
        noshort = true,
        descr = "source IP/ARP address")
    val dstIp = opt[IPv4Addr](
        "dst-ip",
        noshort = true,
        descr = "destination IP/ARP address")
    val proto = opt[IpProtocol](
        "proto",
        noshort = true,
        descr = "IP protocol (TCP, UDP, or ICMP)")
    val tpSrc = opt[Short](
        "src-port",
        noshort = true,
        descr = "transport source port or ICMP type")
    val tpDst = opt[Short](
        "dst-port",
        noshort = true,
        descr = "transport destination port or ICMP code")
    val arp = opt[Boolean](
        "arp",
        default = Some(false),
        noshort = true,
        descr = "src-ip and dst-ip belong to an ARP")
    val arpReply = opt[Boolean](
        "reply",
        default = Some(false),
        noshort = true,
        descr = "tells whether the ARP is a reply")
    val del = opt[Boolean](
        "delete",
        default = Some(false),
        short = 'D',
        descr = "delete the flow")

    dependsOnAll(arpReply, List(arp))
    dependsOnAll(tpSrc, List(proto))
    dependsOnAll(tpDst, List(proto))
    dependsOnAny(srcIp, List(arp, proto))
    dependsOnAny(dstIp, List(arp, proto))
    conflicts(arp, List(tpSrc, tpDst, proto))

    implicit class FlowMatchOpt[_](val opt: ScallopOption[_]) extends AnyVal {
        def seeIfDefined(fmatch: FlowMatch) = opt.get foreach { _ => opt match {
            case `inPort` => fmatch.getInputPortNumber
            case `srcMac` => fmatch.getEthSrc
            case `dstMac` => fmatch.getEthDst
            case `srcIp` => fmatch.getNetworkSrcIP
            case `dstIp` => fmatch.getNetworkDstIP
            case `proto` => fmatch.getNetworkProto
            case `tpSrc` => fmatch.getSrcPort
            case `tpDst` => fmatch.getDstPort
            case _ =>
        }}
    }

    def run(ctx: DpCtx): Unit = {
        val dp = ctx.getDatapath(this.dp.get.get)
        val inPort = ctx.getOrCreateDpPort(dp, this.inPort.get.get)
        val fmatch = buildFlowMatch(inPort)

        if (del.get.get) {
            println(s"Deleting flow $fmatch")
            ctx.deleteFlow(dp, fmatch)
        } else {
            val outPort = ctx.getOrCreateDpPort(dp, this.outPort.get.get)
            val mask = if (dp.supportsMegaflow()) {
            val mask = new FlowMask()
                mask.calculateFor(fmatch)
                mask
            } else null
            val actions = Arrays.asList[FlowAction](FlowActions.output(outPort.getPortNo))
            println(s"Creating flow for $fmatch with ${if (mask ne null) mask else "no mask"}, " +
                    s"from ${inPort.getName} to ${outPort.getName}")
            ctx.createFlow(dp, fmatch, actions, mask)
        }
    }

    private def buildFlowMatch(inPort: DpPort): FlowMatch = {
        val fmatch = new FlowMatch()
        fmatch.addKey(FlowKeys.inPort(inPort.getPortNo))
        this.inPort.seeIfDefined(fmatch)
        prepareL2(fmatch)
        fmatch
    }

    private def prepareL2(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.ethernet(
            srcMac.get.getOrElse(NO_MAC).getAddress,
            dstMac.get.getOrElse(NO_MAC).getAddress))
        srcMac.seeIfDefined(fmatch)
        dstMac.seeIfDefined(fmatch)

        if (arp.get.get) {
            prepareArp(fmatch)
            fmatch.addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_ARP))
        } else if (proto.get.isDefined) {
            prepareL3(fmatch)
            fmatch.addKey(FlowKeys.etherType(FlowKeyEtherType.Type.ETH_P_IP))
        }
    }

    private def prepareL3(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.ipv4(
            srcIp.get.getOrElse(NO_IP),
            dstIp.get.getOrElse(NO_IP),
            proto.get.getOrElse(IpProtocol.ICMP)))

        srcIp.seeIfDefined(fmatch)
        dstIp.seeIfDefined(fmatch)
        proto.seeIfDefined(fmatch)

        prepareL4(fmatch)
    }

    private def prepareL4(fmatch: FlowMatch): Unit = {
        fmatch.addKey(proto.get.get match {
            case IpProtocol.ICMP =>
                FlowKeys.icmp(
                    tpSrc.get.getOrElse(0.toShort).toByte,
                    tpDst.get.getOrElse(0.toShort).toByte)
            case IpProtocol.TCP =>
                FlowKeys.tcp(
                    tpSrc.get.getOrElse(0.toShort).toInt,
                    tpDst.get.getOrElse(0.toShort).toInt)
            case IpProtocol.UDP =>
                FlowKeys.udp(
                    tpSrc.get.getOrElse(0.toShort).toInt,
                    tpDst.get.getOrElse(0.toShort).toInt)
            case _ =>
                throw new Exception("Unrecognized protocol")
        })

        tpSrc.seeIfDefined(fmatch)
        tpDst.seeIfDefined(fmatch)
    }

    private def prepareArp(fmatch: FlowMatch): Unit = {
        fmatch.addKey(FlowKeys.arp(
            NO_MAC.getAddress,
            NO_MAC.getAddress,
            if (arpReply.get.get) 2 else 1,
            srcIp.get.getOrElse(NO_IP).toInt,
            dstIp.get.getOrElse(NO_IP).toInt))

        srcIp.seeIfDefined(fmatch)
        dstIp.seeIfDefined(fmatch)
        proto.seeIfDefined(fmatch)
    }
}

object DatapathCtl extends Subcommand("datapath") with DpCommand {

    descr("datapath operations (create, delete, list, dump)")

    val add = opt[String](
        "add",
        descr = "add a new datapath")
    val del = opt[String](
        "delete",
        short = 'D',
        descr = "delete a datapath")
    val show = opt[String](
        "show",
        descr = "show all the information related to a given datapath")
    val dump = opt[String](
        "dump",
        descr = "show all the flows installed for a given datapath")
    val list = opt[Boolean](
        default = Some(false),
        descr = "list all the installed datapaths")

    requireOne(add, del, show, dump, list)

    def run(ctx: DpCtx): Unit = {
        if (add.get.isDefined) {
            ctx.createDatapath(add.get.get)
            println("Datapath created successfully")
        }

        if (del.get.isDefined) {
            ctx.deleteDatapath(del.get.get)
            println("Datapath deleted successfully")
        }

        if (list.get.get) {
            val dps = ctx.listDatapaths()
            if (dps.isEmpty) {
                println("Could not find any installed datapath")
            } else {
                println(s"Found ${dps.size} datapath${if (dps.size > 1) "s" else ""}")
            }
            dps foreach { dp => println(s"  ${dp.getName}") }
        }

        if (show.get.isDefined) {
            val dp = ctx.getDatapath(show.get.get)
            val ports = ctx.listDpPorts(dp)
            val stats = dp.getStats()
            println(
                s"Name: ${dp.getName}\n" +
                s"Index: ${dp.getIndex}\n" +
                s"Supports megaflows: ${if (dp.supportsMegaflow()) "yes" else "no"}\n" +
                s"Stats:\n" +
                s"  Flows  ${stats.getFlows}\n" +
                s"  Hits   ${stats.getHits}\n" +
                s"  Lost   ${stats.getLost}\n" +
                s"  Misses ${stats.getMisses}")
            ports foreach { p =>
                println(s"Port #${p.getPortNo} '${p.getName}' ${p.getType} ${p.getStats}")
            }
        }

        if (dump.get.isDefined) {
            val dp = ctx.getDatapath(dump.get.get)
            val flows = ctx.dumpDatapath(dp)
                .sortWith(_.getLastUsedMillis < _.getLastUsedMillis)
            println(s"${flows.size} flow${if (flows.size == 1) "" else "s"}")
            flows foreach { flow =>
                println("  Flow")
                flow.toPrettyStrings foreach { f => println(s"    $f") }
            }
        }
    }
}

object InterfaceCtl extends Subcommand("interface") with DpCommand {
    descr("interface operations (add, delete)")

    val add = opt[String](
        "add",
        descr = "add an interface to a datapath")
    val del = opt[String](
        "delete",
        descr = "delete an interface from a datapath")
    val datapath = trailArg[String](
        descr = "the datapath where to create/delete a flow",
        required = true)

    requireOne(add, del)

    def run(ctx: DpCtx): Unit = {
        if (add.get.isDefined) {
            ctx.createDpPort(ctx.getDatapath(datapath.get.get), add.get.get)
            println("Interface added to datapath")
        }

        if (del.get.isDefined) {
            ctx.deleteDpPort(ctx.getDatapath(datapath.get.get), del.get.get)
            println("Interface deleted from datapath")
        }
    }
}

object MmDpctl extends App {
    val SUCCESS = 0
    val FAILURE = 1
    val TIMEOUT = 1

    val opts = new ScallopConf(args) {
        DatapathCtl
        InterfaceCtl
        FlowsCtl
        val timeout = opt[Long](
            "timeout",
            default = Some(3),
            noshort = true,
            descr = "timeout in seconds to wait for an operation to complete")
        printedName = "mm-dpctl"
        footer("Copyright (c) 2015 Midokura SARL, All Rights Reserved.")
    }

    val ret = try {
        val channel = new NetlinkChannelFactory().create(blocking = false)
        val timeout = Duration(opts.timeout.get.get, TimeUnit.SECONDS)
        val ctx = new DpCtx(channel, 2 * 1024 * 1024, timeout)

        if (opts.subcommand.isDefined) {
            opts.subcommand.get.asInstanceOf[DpCommand].run(ctx)
            SUCCESS
        } else {
            opts.printHelp()
            FAILURE
        }
    } catch {
        case e: TimeoutException =>
            System.err.println(s"[\033[31m${opts.printedName}\033[0m] Error: Request timeout")
            TIMEOUT
        case e: NetlinkException =>
            System.err.println(s"[\033[31m${opts.printedName}\033[0m] Error: ${e.getMessage}")
            FAILURE
        case NonFatal(e) =>
            var t = e
            while ((t.getCause ne null) && (t.getMessage eq null))
                t = e.getCause
            System.err.println(s"[\033[31m${opts.printedName}\033[0m] Unexpected error: ${t.getMessage}")
            t.printStackTrace(System.err)
            FAILURE
    }

    System.exit(ret)
}
