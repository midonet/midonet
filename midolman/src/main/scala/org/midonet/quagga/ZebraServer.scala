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

package org.midonet.quagga

import scala.actors._
import scala.actors.Actor._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import java.io.IOException
import java.net.{Socket, SocketAddress}

import org.slf4j.LoggerFactory
import org.midonet.packets.IPAddr
import org.newsclub.net.unix.AFUNIXServerSocket

case class Request(socket: Socket, reqId: Int)
case class Response(reqId: Int)

trait ZebraServerService {
    def start()
    def stop()
}

class ZebraServer(val address: SocketAddress,
                  val handler: ZebraProtocolHandler,
                  val ifAddr: IPAddr,
                  val ifName: String)
    extends ZebraServerService {

    private final val log = LoggerFactory.getLogger(this.getClass)
    private var run = false

    // Pool of Actors.
    val zebraConnPool = ListBuffer[ZebraConnection]()
    val zebraConnMap = mutable.Map[Int, ZebraConnection]()

    val server = AFUNIXServerSocket.newInstance()

    val dispatcher = actor {

        def addZebraConn(dispatcher: Actor, requestId: Int) {
            log.debug("begin, requestId: {}", requestId)
            val zebraConn =
                new ZebraConnection(dispatcher, handler, ifAddr, ifName, requestId)
            log.debug("ifAddr: {}, ifName: {}", ifAddr, ifName)
            zebraConnPool += zebraConn
            zebraConn.start()
            log.debug("end")
        }

        loop {
            react {
                case Request(conn: Socket, requestId: Int) => {
                    log.debug("client id {}", requestId)
                    if (zebraConnPool.isEmpty) {
                        addZebraConn(self, requestId)
                    }
                    val zebraConn = zebraConnPool.remove(0)
                    zebraConnMap(requestId) = zebraConn
                    zebraConn ! HandleConnection(conn)
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


    def start() {
        run = true
        log.info("start")

        actor {
            log.debug("zebra actor initialized")
            var requestId = 0
            loopWhile(run) {
                try {
                    // this is blocking
                    // from: http://www.scala-lang.org/node/242
                    // "[scala] Actors are executed on a thread pool. Initially,
                    // there are 4 worker threads. The thread pool grows if all
                    // worker threads are blocked but there are still remaining
                    // tasks to be processed"
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

    def stop() {
        log.info("stop")
        run = false
        server.close()
    }

}


