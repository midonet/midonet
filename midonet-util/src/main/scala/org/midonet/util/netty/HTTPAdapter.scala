/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.util.netty

import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelInitializer, ChannelPipeline}
import io.netty.handler.codec.http.{HttpContentCompressor, HttpContentDecompressor, HttpObjectAggregator, HttpServerCodec}
import io.netty.handler.ssl.SslContext
import io.netty.handler.stream.ChunkedWriteHandler

/**
  * Basic channel adapter for HTTP communication through netty.
  *
  * @param sslCtx Optional ssl context. If provided, will initialize ssl handler
  */
class HTTPAdapter(sslCtx: Option[SslContext] = None,
                  maxLength: Int = HTTPAdapter.DEFAULT_MAX_LENGTH)
    extends ChannelInitializer[SocketChannel] {
    override def initChannel(ch: SocketChannel) = {
        val pipe: ChannelPipeline = ch.pipeline()

        sslCtx.foreach(ssl => pipe.addLast(ssl.newHandler(ch.alloc())))
        pipe.addLast("http-codec", new HttpServerCodec())
        pipe.addLast("http-compress", new HttpContentCompressor())
        pipe.addLast("http-decompress", new HttpContentDecompressor())
        pipe.addLast("http-aggregator", new HttpObjectAggregator(maxLength))
        pipe.addLast("http-chunk-write-handler", new ChunkedWriteHandler())
    }
}

object HTTPAdapter {
    val DEFAULT_MAX_LENGTH = 65536
}
