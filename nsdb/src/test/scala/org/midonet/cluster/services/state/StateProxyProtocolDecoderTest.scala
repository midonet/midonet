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

package org.midonet.cluster.services.state

import scala.util.Random

import io.netty.buffer.{CompositeByteBuf, Unpooled, UnpooledByteBufAllocator}
import io.netty.channel.ChannelHandlerContext

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import org.midonet.cluster.rpc.State.Message

@RunWith(classOf[JUnitRunner])
class StateProxyProtocolDecoderTest extends FlatSpec with Matchers
                                    with GivenWhenThen {

    val random = new Random()

    "Decoder" should "decode a valid byte buffer into a message" in {
        Given("A decoder")
        val decoder = new StateProxyProtocolDecoder

        And("A mock context")
        val context = Mockito.mock(classOf[ChannelHandlerContext])

        And("A message written to a buffer")
        val message = Message.newBuilder().setRequestId(random.nextLong()).build()
        val data = Unpooled.wrappedBuffer(message.toByteArray)

        When("Reading the buffer with the decoder")
        decoder.channelRead(context, data)

        Then("The context should read the decoded message")
        Mockito.verify(context).fireChannelRead(message)
    }

    "Decoder" should "decode a valid composite byte buffer into a message" in {
        Given("A decoder")
        val decoder = new StateProxyProtocolDecoder

        And("A mock context")
        val context = Mockito.mock(classOf[ChannelHandlerContext])

        And("A message written to a composite buffer with three components")
        val message = Message.newBuilder().setRequestId(random.nextLong()).build()
        val buffer = new CompositeByteBuf(UnpooledByteBufAllocator.DEFAULT,
                                          false, 256)
        buffer.addComponent(Unpooled.buffer(10))
        buffer.addComponent(Unpooled.buffer(10))
        buffer.addComponent(Unpooled.buffer(10))
        buffer.writeBytes(message.toByteArray)

        When("Reading the buffer with the decoder")
        decoder.channelRead(context, buffer)

        Then("The context should read the decoded message")
        Mockito.verify(context).fireChannelRead(message)
    }

    "Decoder" should "handle an invalid message" in {
        Given("A decoder")
        val decoder = new StateProxyProtocolDecoder

        And("A mock context")
        val context = Mockito.mock(classOf[ChannelHandlerContext])

        And("An empty buffer")
        val buffer = Unpooled.buffer()

        When("Reading the buffer with the decoder")
        decoder.channelRead(context, buffer)

        Then("The context should read the original buffer")
        Mockito.verify(context).fireChannelRead(buffer)
    }

}
