// Copyright 2011 Midokura Inc.

// DummyOVSDB.scala - Dummy Open vSwitch database server for testing
//                    OpenvSwitchDatabaseConnection.


import java.net.{ServerSocket, Socket}
import org.slf4j.LoggerFactory


object DummyOVSDB {
    val defaultPort = 12343
}

class DummyOVSDB(val port:Int=DummyOVSDB.defaultPort) {
    private val listenSocket = new ServerSocket(port)
    
    def accept[T <: DummyOVSDBServerConn](clazz: Class[T]): T = {
        val acceptSocket = listenSocket.accept
        val constructor = clazz.getConstructor(acceptSocket.getClass)
        constructor.newInstance(acceptSocket)
    }
}


class DummyOVSDBServerConn(protected var socket: Socket,
                           val bufSize:Int=1000) {
    protected val outStream = socket.getOutputStream
    protected val inStream = socket.getInputStream

    final val log = LoggerFactory.getLogger(getClass)

    def loop(): Nothing = {
        var buf = new Array[Byte](bufSize)
    
        while (true) {
            val bytesRead = inStream.read(buf)
            val message = new String(buf, 0, bytesRead, "ASCII")

            if (message.startsWith("""{"method":"echo""""))
                handleEcho(message)
            else if (message.startsWith("""{"method":"transact""""))
                handleTransact(message)
            else
                log.error("Unknown message type received: {}", message)
        }
        throw new RuntimeException("Infinite loop ended!!")
    }

    def handleEcho(message: String) {
        // TODO
    }

    def handleTransact(message: String) {
        // TODO
    }
}
