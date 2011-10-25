// Copyright 2011 Midokura Inc.

package com.midokura.midolman.openvswitch


import java.net.{InetAddress, Socket}
import scala.concurrent.ops.spawn

import org.junit.Test
import org.junit.Assert._


class TestDummyOVSDB {
    @Test def testEcho() {
        val ovsdb = new DummyOVSDB()
        spawn { 
            ovsdb.accept(classOf[DummyOVSDBServerConn]).loop
            ovsdb.close
        }
        Thread.sleep(20)
        val localhost = InetAddress.getByName("127.0.0.1")
        val socket = new Socket(localhost, DummyOVSDB.defaultPort)
        val outStream = socket.getOutputStream
        outStream.write("""{"method":"echo","params":[],"id":"echo"}""".getBytes("ASCII"))
        var buf = new Array[Byte](100)
        val inStream = socket.getInputStream
        val bytesRead = inStream.read(buf)
        val response = new String(buf, 0, bytesRead, "ASCII")
        assertEquals("""{"id":"echo","error":null,"result":[]}""", response)
        outStream.write("""{"method":"die","params":[],"id":0}""".getBytes("ASCII"))
    }
}
