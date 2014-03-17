/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.sys.process.Process

import org.midonet.util.BatchCollector
import org.midonet.netlink._
import org.midonet.netlink.exceptions._
import org.midonet.odp.family._
import org.midonet.odp.ports._
import org.midonet.odp.flows._
import org.midonet.odp.protos._

object OvsSingleThreadThroughputTest {

    abstract class BaseHandler extends BatchCollector[Packet] {
        val counter = new java.util.concurrent.atomic.AtomicInteger(0)
        def handlePacket(p: Packet)
        def submit(p: Packet) {
            counter.getAndIncrement()
            handlePacket(p)
        }
        def endBatch() { }
        def stats() = counter.getAndSet(0)
    }

    class Reader extends BaseHandler {
        def handlePacket(p: Packet) { }
    }

    class Forwarder(con: OvsConnectionOps, dp: Datapath) extends BaseHandler {
        val output = new java.util.ArrayList[FlowAction]()
        output.add(FlowActions.output(2))
        def handlePacket(p: Packet) {
            p setActions output
            con.execPacket(p, dp)
        }
    }

    def prepareDatapath(dpName: String, ifName: String) = {
        val con = new OvsConnectionOps(DatapathClient.createConnection())

        val dpF = con.ensureDp(dpName)
        Await.result(dpF flatMap{ con.ensureNetDevPort(ifName, _) }, 2 seconds)

        (con, Await.result(dpF, 2 seconds))
    }

    def runLoop(n: Int, handler: BaseHandler, con: OvsConnectionOps, dp: Datapath) {
        Await.result(con.setHandler(dp, handler), 2 seconds)
        handler.stats
        (0 until n) foreach { case _ =>
          val packetHandled = handler.stats
          println(s"$packetHandled pps")
          Thread sleep 1000
        }
    }

}

object OvsPacketReadThroughputTest {

    import OvsSingleThreadThroughputTest._

    def main(args: Array[String]) {
        val (con, dp) = prepareDatapath("perftest", "perft-if")

        val mzcommand = args.reduce { _ + " " + _}
        println("executing: " + mzcommand)
        val mz = Process(mzcommand).run()

        println("packet read throughput")
        runLoop(10, new Reader, con, dp)

        mz.destroy()

        System exit 0
    }

}
