// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch


import java.net.{InetAddress, Socket}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ops.spawn

import org.junit.{Ignore, Test}
import org.junit.Assert._
import org.slf4j.LoggerFactory

import OpenvSwitchException.OVSDBException

object TestDummyOVSDB {
    val counter = new AtomicInteger()
    val log = LoggerFactory.getLogger(classOf[TestDummyOVSDB])
    final val basePortNum = 12342
    val localhost = InetAddress.getByName("127.0.0.1")

    val portUuid1 = "43df8a41-df44-b4a8-8f06-db969c0ebfa5"
    val bridgeUuid1 = "aff7ae0e-9363-4447-b2a8-3de70792016d"

    def startOVSDB[T <: DummyOVSDBServerConn](clazz: Class[T]): Int = {
        val portNum = basePortNum - counter.incrementAndGet
        val ovsdb = new DummyOVSDB(portNum)
        spawn {
            ovsdb.accept(clazz).loop
            ovsdb.close
        }          
        Thread.sleep(20)
        return portNum
    }

    def checkOVSDB(clazz: Class[_ <: DummyOVSDBServerConn],
                   request: String, expectedResponse: String) {
        val portNum = startOVSDB(clazz)
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
}

class TestDummyOVSDB {
    import TestDummyOVSDB._

    @Test(timeout=1000) def testEcho() {
        checkOVSDB(classOf[DummyOVSDBServerConn],
                   """{"method":"echo","params":[],"id":"echo"}""",
                   """{"id":"echo","error":null,"result":[]}""")
    }

    @Test(timeout=1000) def testTransact() {
        checkOVSDB(classOf[DummyOVSDBServerConn],
                   """{"method":"transact","params":["Open_vSwitch",""" +
                   """{"op":"select","table":"Bridge","where":[],"columns":""" +
                   """["_uuid","name","ports"]}],"id":1}""",
                   """{"id":1,"error":null,"result":[{"rows":[]}]}""")
    }

    @Test(timeout=1000) def testBasicOVSDBConnection() {
        val portNum = startOVSDB(classOf[DummyOVSDBServerConn])
        val ovsdbConn = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                "localhost", portNum)
        try {
            val bridgeTable = ovsdbConn.dumpBridgeTable
            assertTrue(bridgeTable.isEmpty)
        } finally {
            ovsdbConn.close
        }
    }

    @Test(timeout=1000) def testDumpBridgeTable() {
        val portNum = startOVSDB(classOf[OVSDBWithBridgeTable])
        val ovsdbConn = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                "localhost", portNum)
        try {
            val bridgeTable = ovsdbConn.dumpBridgeTable
            assertEquals(1, bridgeTable.size)
            val row = bridgeTable.head
            assertEquals("public", row._1)
            assertEquals(bridgeUuid1, row._2)
            assertEquals("""["set",[["uuid","""" + portUuid1 + "\"]]]",
                         row._3.toString)
        } finally {
            ovsdbConn.close
        }
    }

    @Test(timeout=10000, expected = classOf[OVSDBException])
    def testImmediateDisconnection() {
        var ovsdbConnSuccessful = false
        var ovsdbConn: OpenvSwitchDatabaseConnectionImpl = null
        try {
            val portNum = startOVSDB(classOf[OVSDBImmediatelyDisconnects])
            ovsdbConn = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                                "localhost", portNum)
            ovsdbConnSuccessful = true
            val bridgeTable = ovsdbConn.dumpBridgeTable
            ovsdbConnSuccessful = false   // Shouldn't be reached
        } finally {
            assertTrue(ovsdbConnSuccessful)
            ovsdbConn.close
        }
    }
}


// TODO: Why doesn't this work as an inner class?
private class OVSDBWithBridgeTable(sokket: Socket) 
                extends DummyOVSDBServerConn(sokket) {
    override def handleTransact(params: String, id: String) {
        if (params == """["Open_vSwitch",{"op":"select","table":"Bridge","where":[],"columns":["_uuid","name","ports"]}]""") {
            outStream.write(String.format("""{"id":%s,"error":null,"result":[{"rows":[{"ports":["set",[["uuid","%s"]]],"_uuid":["uuid","%s"],"name":"public"}]}]}""", id, TestDummyOVSDB.portUuid1, TestDummyOVSDB.bridgeUuid1).getBytes("ASCII"))
        } else {
            super.handleTransact(params, id)
        }
    }
}


private class OVSDBImmediatelyDisconnects(sokket: Socket) 
                extends DummyOVSDBServerConn(sokket) {
    override def handleTransact(params: String, id: String) {
        if (params == """["Open_vSwitch",{"op":"select","table":"Bridge","where":[],"columns":["_uuid","name","ports"]}]""") {
            log.info("Closing socket")
            socket.shutdownOutput
            socket.shutdownInput
            socket.close
        } else {
            super.handleTransact(params, id)
        }
    }
}
