/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import org.slf4j.LoggerFactory

import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.util.ReferenceCountUtil

/**
  * Handler usually added to the end of the pipeline that simply closes the
  * channel if an exception goes through the entire pipeline without
  * being consumed.
  */
class CloseOnExceptionHandler extends ChannelInboundHandlerAdapter {
    private val log = LoggerFactory.getLogger(classOf[CloseOnExceptionHandler])

    override def exceptionCaught(ctx: ChannelHandlerContext,
                                 cause: Throwable): Unit = {
        try {
            cause.printStackTrace()
            log.warn("Unhandled exception reached end of pipeline", cause)
            ctx.close()
        } catch {
            case e: Exception =>
                e.printStackTrace()
                log.error("Error closing channel after exception reached " +
                          "end of pipeline")
        } finally {
            ReferenceCountUtil.release(cause)
        }
    }
}
