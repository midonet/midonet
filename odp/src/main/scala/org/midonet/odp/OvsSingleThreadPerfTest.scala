/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.test

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

import org.apache.commons.exec._

import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.util.BatchCollector

abstract class OvsSimpleThroughputTest {

    val WAIT_TIMEOUT = 15 * 1000

    abstract class BaseHandler extends BatchCollector[Packet] {

        val counter = new java.util.concurrent.atomic.AtomicInteger(0)

        def handlePacket(p: Packet)

        def submit(p: Packet) {
            counter.getAndIncrement()
            handlePacket(p)
        }

        def endBatch() {}

        def stats() = counter.getAndSet(0)
    }

    class Reader extends BaseHandler {

        def handlePacket(p: Packet) {}
    }

    class Forwarder(con: OvsConnectionOps, dp: Datapath) extends BaseHandler {

        val output = new java.util.ArrayList[FlowAction]()
        output.add(FlowActions.output(2))

        def handlePacket(p: Packet) {
            con firePacket(p, output, dp)
        }
    }

    class AckForwarder(con: OvsConnectionOps, dp: Datapath)
        extends BaseHandler {

        val output = new java.util.ArrayList[FlowAction]()
        output.add(FlowActions.output(2))

        def handlePacket(p: Packet) {
            con execPacket(p, output, dp)
        }
    }

    def runLoop(n: Int, handler: BaseHandler, con: OvsConnectionOps,
                dp: Datapath) {
        Await.result(con.setHandler(dp, handler), 2 seconds)
        handler.stats
        (0 until n) foreach { case _ =>
            val packetHandled = handler.stats
            println(s"$packetHandled pps")
            Thread sleep 1000
        }
    }

    def withPacketsIn(args: Array[String])(block: => Unit): Int = {
        val mzCommand = "ip netns exec ${ns} mz ${nsif} " +
            "-A 100.0.10.2 -B 100.0.10.240 " +
            "-b 10:00:00:00:00:01 -t udp 'sp=1-10000,dp=1-10000' -c0"

        println("Executing command: " + mzCommand)
        val cmdLine = CommandLine.parse(mzCommand)
        val map = mutable.HashMap(
            "ns" -> args(0),
            "nsif" -> args(1))
        cmdLine.setSubstitutionMap(map)

        val resultHandler = new DefaultExecuteResultHandler()
        val executor = new DefaultExecutor()

        // start a watchdog. it will be responsible for killing the test
        // after some time, as it runs forever...
        val watchdog = new ExecuteWatchdog(WAIT_TIMEOUT)
        executor.setExitValue(0)
        executor.setWatchdog(watchdog)
        executor.execute(cmdLine, resultHandler)

        block

        resultHandler.waitFor()
        if (resultHandler.hasResult) {
            val retCode = resultHandler.getExitValue
            println(s"code: $retCode")
            if (retCode == 143) {  // killed by SIGTERM... we must do it
                0
            } else {
                retCode
            }
        } else {
            1
        }
    }

    val (con, dp) = OvsConnectionOps.prepareDatapath("perftest", "perft-if")

    def handler(): BaseHandler

    def main(args: Array[String]) {
        val exitValue = withPacketsIn(args) {
            runLoop(10, handler(), con, dp)
        }
        System exit exitValue
    }
}

object OvsPacketReadTest extends OvsSimpleThroughputTest {

    override def handler(): BaseHandler = new Reader
}

object OvsPacketReadExecTest extends OvsSimpleThroughputTest {

    override def handler(): BaseHandler = new Forwarder(con, dp)
}

object OvsPacketReadExecAckTest extends OvsSimpleThroughputTest {

    override def handler(): BaseHandler = new AckForwarder(con, dp)
}
