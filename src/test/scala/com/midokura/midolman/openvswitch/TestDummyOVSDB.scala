// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch


import java.net.{InetAddress, Socket}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ops.spawn

import org.junit.Test
import org.junit.Assert._
import org.slf4j.LoggerFactory


object TestDummyOVSDB {
    val counter = new AtomicInteger()
    val log = LoggerFactory.getLogger(classOf[TestDummyOVSDB])
    final val basePortNum = 12342
    val localhost = InetAddress.getByName("127.0.0.1")
}

class TestDummyOVSDB {
    import TestDummyOVSDB._

    def checkOVSDB(clazz: Class[DummyOVSDBServerConn],
                   request: String, expectedResponse: String) {
        val portNum = basePortNum - counter.incrementAndGet
        val ovsdb = new DummyOVSDB(portNum)
        spawn { 
            ovsdb.accept(clazz).loop
            ovsdb.close
        }
        Thread.sleep(20)
        val socket = new Socket(localhost, portNum)
        val outStream = socket.getOutputStream
        outStream.write(request.getBytes("ASCII"))
        var buf = new Array[Byte](100)
        val inStream = socket.getInputStream
        val bytesRead = inStream.read(buf)
        val response = new String(buf, 0, bytesRead, "ASCII")
        assertEquals(expectedResponse, response)
        outStream.write("""{"method":"die","params":[],"id":0}""".getBytes("ASCII"))
    }

    @Test(timeout=1000) def testEcho() {
        checkOVSDB(classOf[DummyOVSDBServerConn],
                   """{"method":"echo","params":[],"id":"echo"}""",
                   """{"id":"echo","error":null,"result":[]}""")
    }

    @Test(timeout=1000) def testTransact() {
        val portNum = basePortNum - counter.incrementAndGet
        log.info("testTransact: Using port {}", portNum)
        val ovsdb = new DummyOVSDB(portNum)
        spawn {
            ovsdb.accept(classOf[DummyOVSDBServerConn]).loop
            ovsdb.close
        }
        Thread.sleep(20)
        val socket = new Socket(localhost, portNum)
        val outStream = socket.getOutputStream
        outStream.write("""{"method":"transact","params":["Open_vSwitch",{"op":"select","table":"Bridge","where":[],"columns":["_uuid","name","ports"]}],"id":1}""".getBytes("ASCII"))
        var buf = new Array[Byte](100)
        val inStream = socket.getInputStream
        val bytesRead = inStream.read(buf)
        val response = new String(buf, 0, bytesRead, "ASCII")
        assertEquals("""{"id":1,"error":null,"result":[{"rows":[]}]}""", response)
        outStream.write("""{"method":"die","params":[],"id":0}""".getBytes("ASCII"))
    }

}
