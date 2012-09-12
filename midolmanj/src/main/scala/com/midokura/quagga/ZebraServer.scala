/**
 * ZebraServer.scala - Quagga Zebra server classes.
 *
 * A pure Scala implementation of the Quagga Zebra server to connect
 * MidoNet and protocol daemons like BGP, OSPF and RIP.
 *
 * This module can connected using a Unix domain or a TCP server socket,
 * where Quagga uses a Unix domain socket by default.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.quagga

import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import java.io.IOException
import java.net.{ServerSocket, Socket, SocketAddress}

import org.slf4j.LoggerFactory
import com.midokura.packets.IntIPv4
import com.midokura.midolman.routingprotocols.ZebraProtocolHandler

case class Request(socket: Socket, reqId: Int)
case class Response(reqId: Int)

trait ZebraServer {
    def start()
    def stop()
}

class ZebraServerImpl(val server: ServerSocket,
                      val address: SocketAddress,
                      val handler: ZebraProtocolHandler,
                      val ifAddr: IntIPv4,
                      val ifName: String)
    extends ZebraServer {

    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false

    // Pool of Actors.
    val zebraConnPool = ListBuffer[ZebraConnection]()
    val zebraConnMap = mutable.Map[Int, ZebraConnection]()

    val dispatcher = actor {

        def addZebraConn(dispatcher: Actor) {
            val zebraConn =
                new ZebraConnection(dispatcher, handler, ifAddr, ifName)
            zebraConnPool += zebraConn
            zebraConn.start
        }

        loop {
            react {
                case Request(conn: Socket, requestId: Int) => {
                    if (zebraConnPool.isEmpty) {
                        addZebraConn(self)
                    }
                    val zebraConn = zebraConnPool.remove(0)
                    zebraConnMap(requestId) = zebraConn
                    zebraConn ! Request(conn, requestId)
                }
                case Response(requestId) => {
                    log.debug("dispatcher: response %d".format(requestId))
                    // Return the worker to the pool.
                    zebraConnMap.remove(requestId).foreach(zebraConnPool += _)
                }
                case _ =>
                    { log.error("dispatcher: unknown message received.") }
            }
        }
    }

    server.bind(address)


    override def start() {
        run = true
        log.info("start")

        actor {
            var requestId = 0
            loopWhile(run) {
                try {
                    val conn = server.accept
                    log.debug("start.actor accepted connection {}", conn)

                    dispatcher ! Request(conn, requestId)
                    requestId += 1
                } catch {
                    case e: IOException => {
                        log.error("accept failed", e)
                        run = false
                    }
                }
            }
        }
    }

    override def stop() {
        log.info("stop")
        run = false
        server.close()
    }
}
