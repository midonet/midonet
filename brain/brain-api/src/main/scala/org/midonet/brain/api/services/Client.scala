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

package org.midonet.brain.api.services

import java.util.UUID

import org.slf4j.LoggerFactory

import io.netty.channel.ChannelHandlerContext
import org.midonet.cluster.models.Commons

import io.netty.util.ReferenceCountUtil

/**
 * Connection client data
 */
class Client(val id: UUID) {
    private val log = LoggerFactory.getLogger(classOf[Client])

    private var ctx: ChannelHandlerContext = null
    private var lastMsg: Commons.UUID = null


    private val senderThread = new Thread(new Runnable {
        def run(): Unit = {
            log.info("starting client thread")
            while (!Thread.currentThread().isInterrupted) try {
                Thread.sleep(10000)
                if (lastMsg != null)
                    send(lastMsg)
            } catch {
                case e: Throwable => Thread.currentThread().interrupt()
            }
            log.info("stopping client thread")
        }
    })
    senderThread.start()

    def connect(c: ChannelHandlerContext) = {
        if (ctx != null) log.warn("client {} already connected", id)
        else {
            ctx = c
            senderThread.start()
        }
    }

    def disconnect(c: ChannelHandlerContext) = {
        if (ctx != c) log.warn("client {} not connected", id)
        else {
            if (lastMsg != null) ReferenceCountUtil.release(lastMsg)
            senderThread.interrupt()
            ctx = null
        }
    }

    def error(c: ChannelHandlerContext, e: Throwable) = {
        log.warn("client {} detected error", id, e)
    }

    def send(msg: Commons.UUID) = {
        ctx.writeAndFlush(msg)
        this
    }

    def receive(msg: Commons.UUID) = {
        if (lastMsg != null) ReferenceCountUtil.release(lastMsg)
        lastMsg = msg
        send(lastMsg)
    }
}
