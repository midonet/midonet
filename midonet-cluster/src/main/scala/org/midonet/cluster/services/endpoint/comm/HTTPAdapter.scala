/*
 * Copyright (c) 2016 Midokura SARL
 */

package org.midonet.cluster.services.endpoint.comm

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
