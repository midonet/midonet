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

package org.midonet.brain.api.services;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulate a binary protocol buffer encoding into a websocket binary frame
 */
public class BinaryToWSFrameEncoder extends MessageToMessageEncoder<ByteBuf> {
    private static final Logger log =
        LoggerFactory.getLogger(BinaryToWSFrameEncoder.class);

    @Override
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        log.debug("checking message of type {}", msg.getClass());
        return super.acceptOutboundMessage(msg);
    }

    @Override
    public void encode(ChannelHandlerContext ctx, ByteBuf data,
                       List<Object> out) throws Exception {
        log.debug("transcoding byte buffer to binary ws frame");
        log.debug("wrapping {} bytes into a binary ws frame",
                  data.readableBytes());
        WebSocketFrame frame = new BinaryWebSocketFrame(data.copy());
        out.add(frame);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("exception transcoding byte buffer to binary ws frame", cause);
        ctx.close();
    }
}
