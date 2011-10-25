// Copyright 2011 Midokura Inc.

// DummyOVSDB.scala - Dummy Open vSwitch database server for testing
//                    OpenvSwitchDatabaseConnection.

package com.midokura.midolman.openvswitch


import java.lang.reflect.InvocationTargetException
import java.net.{ServerSocket, Socket}
import org.slf4j.LoggerFactory


object DummyOVSDB {
    val defaultPort = 12343
}

class DummyOVSDB(val port:Int=DummyOVSDB.defaultPort) {
    private val listenSocket = new ServerSocket(port)
    final val log = LoggerFactory.getLogger(classOf[DummyOVSDB])
    
    def accept[T <: DummyOVSDBServerConn](clazz: Class[T]): T = {
        try {
            log.info("Waiting for connection...")
            val acceptSocket = listenSocket.accept
            log.info("Connection established.")
            val constructor = clazz.getConstructor(acceptSocket.getClass)
            log.info("Constructing new connection object.")
            val connobj = constructor.newInstance(acceptSocket)
            log.info("New connection object constructed.")
            return connobj
        } catch { 
            case e: InvocationTargetException =>
                val d = e.getCause
                log.error("constructor threw {} {}", d.getMessage, d)
                throw d
            case e: Exception =>
                log.error("accept() threw ", e)
                throw e
        }
    }

    def close() { listenSocket.close }
}


class DummyOVSDBServerConn(protected var socket: Socket) {
    val bufSize = 1000
    protected val outStream = socket.getOutputStream
    protected val inStream = socket.getInputStream

    final val log = LoggerFactory.getLogger(getClass)

    // Warning, fragile RE parsing used.
    val requestRE = """ *\{"method":"([a-z]+)","params":(\[.*\]),"id":(.+)\}""".r

    def loop(): Nothing = {
        log.info("Entering loop()")
        var buf = new Array[Byte](bufSize)
    
        while (true) {
            log.info("Waiting for data from socket...")
            val bytesRead = inStream.read(buf)
            val message = new String(buf, 0, bytesRead, "ASCII")

            log.info("Received message: {}", message)
            message match {
                case requestRE("echo", params, id) => 
                        handleEcho(params, id)
                case requestRE("transact", params, id) => 
                        handleTransact(params, id)
                case requestRE("die", _, _) =>
                        socket.close
                        throw new RuntimeException("Dying by request")
                case _ => 
                        log.error("Unknown message type received: '{}'", message)
            }
        }
        throw new RuntimeException("Infinite loop ended!!")
    }

    def handleEcho(params: String, id: String) {
        outStream.write(String.format("""{"id":%s,"error":null,"result":%s}""",
                                      id, params).getBytes("ASCII"))
    }

    def handleTransact(params: String, id: String) {
        val opRE = """\["Open_vSwitch",\{"op":"([a-z]+)","table":"([a-zA-Z]+)",(.*)\}\]""".r
        params match {
            case opRE("select", unusedTable, unusedCdr) =>
                outStream.write(String.format("""{"id":%s,"error":null,"result":[{"rows":[]}]}""", id).getBytes("ASCII"))
            case _ =>
                log.error("Unknown transact type received: {}", params)
        }
    }
}
