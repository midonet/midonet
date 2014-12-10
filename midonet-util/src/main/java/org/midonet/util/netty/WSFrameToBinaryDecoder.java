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

package org.midonet.util.netty;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extract a binary byte buffer from a binary websocket frame.
 * Note: this is intended for extracting a binary encoded protocol buffer
 * message from a binary websocket frame, but it should work for any other
 * binary frame content.
 */
public class WSFrameToBinaryDecoder
    extends MessageToMessageDecoder<BinaryWebSocketFrame> {
    private static final Logger log =
        LoggerFactory.getLogger(WSFrameToBinaryDecoder.class);

    @Override
    public void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame,
                       List<Object> out) {
        ByteBuf buf = frame.content();
        out.add(buf.retain());
        // TODO: check if frame reference counter should be decreased
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("exception transcoding ws frame to byte buffer", cause);
        ctx.close();
    }
}
