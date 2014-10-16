/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
