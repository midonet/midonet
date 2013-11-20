/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.quagga

import org.scalatest.{Matchers, FunSuite, BeforeAndAfter}
import java.io._
import org.scalatest.concurrent.Eventually

class MockVtyConnection(addr: String, port: Int, password: String, keepAliveTime: Int,
                           holdTime: Int, connectRetryTime: Int)
    extends VtyConnection (addr, port, password, keepAliveTime, holdTime, connectRetryTime) {

    // Limiting the buffer size to 10 bytes allows us to simulate messages
    // received in chunks as it happens in real life with Vty connections
    override val BufferSize = 10

    val outStream = new ByteArrayOutputStream(4096)

    override def openConnection() {
        // avoiding socket initialization
    }

    override def closeConnection() {
        // avoiding socket closing
    }
}

class VtyConnectionTest extends FunSuite
    with BeforeAndAfter
    with Eventually
    with Matchers {

    var vty: MockVtyConnection = _

    before {
        vty = new MockVtyConnection(
            addr = "addr",
            port = 0,
            password = "password",
            keepAliveTime = 60,
            holdTime = 180,
            connectRetryTime = 120)
    }

    test("VtyConnection creation") {
        assert(vty != null)
    }

    test("recvMessage") {
        val msgList = List(
            "123456789",
            "ABCDEFGHIJ",
            "abcdefghijk",
            "1234",
            "ABCD",
            "abcd",
            "El perro de San Roque no tiene rabo",
            "1",
            "And the story ends."
        )

        val msgString = msgList.mkString("\n")

        val msg = msgString.toCharArray.map(_.toByte)
        vty.in = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(msg)))
        assert(vty.in.ready() == true)
        vty.connected = true

        val lines: Seq[String] = vty.recvMessage(9)

        lines should have length (9)
        lines should equal (msgList)
    }

}
