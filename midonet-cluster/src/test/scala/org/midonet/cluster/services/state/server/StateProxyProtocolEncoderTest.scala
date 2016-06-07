/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.state.server

import scala.util.Random

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.rpc.State.Message

@RunWith(classOf[JUnitRunner])
class StateProxyProtocolEncoderTest extends FlatSpec with Matchers
                                    with GivenWhenThen {

    val random = new Random()

    "Encoder" should "encode a message to a byte buffer" in {
        Given("An encoder")
        val encoder = new StateProxyProtocolEncoder

        And("A mock context")
        val context = Mockito.mock(classOf[ChannelHandlerContext])

        And("A message")
        val message = Message.newBuilder().setRequestId(random.nextLong()).build()

        When("Writing a message to the encoder")
        encoder.write(context, message, null)

        Then("The encoder should write the encoded message to the context")
        Mockito.verify(context).write(Unpooled.wrappedBuffer(message.toByteArray), null)
    }

}
