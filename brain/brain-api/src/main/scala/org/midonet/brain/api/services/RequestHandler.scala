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

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory
import rx.functions.{Action0, Action1}
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.rpc.Commands

/**
 * Requests from netty framework
 */
abstract class Request
case class Connect(ctx: ChannelHandlerContext) extends Request
case class Disconnect(ctx: ChannelHandlerContext) extends Request
case class Error(ctx: ChannelHandlerContext, exc: Throwable) extends Request
case class Proto(ctx: ChannelHandlerContext, proto: Commands.Request) extends Request

/**
 * Processes the requests from the server front-ends
 */
class RequestHandler(private val connMgr: ConnectionManager) {
    private val log = LoggerFactory.getLogger("RequestHandler")

    val subject: Subject[Request, Request] = PublishSubject.create()

    subject.subscribe(new Action1[Request] {
        override def call(req: Request): Unit = req match {
            case Connect(ctx) =>
                connMgr.get(ctx)
                log.info("api connection established")
            case Disconnect(ctx) =>
                connMgr.get(ctx).disconnect
                log.info("api connection terminated")
            case Error(ctx, exc) =>
                connMgr.get(ctx).error(exc)
                log.error("api connection error")
            case Proto(ctx, pb) =>
                connMgr.get(ctx).msg(pb)
                log.trace("protobuf received")
        }
    }, new Action1[Throwable] {
        override def call(e: Throwable): Unit = {
            log.error("broken link with topology service clients", e)
        }
    }, new Action0 {
        override def call(): Unit = {
            log.error("terminated link with topology service clients")
        }
    })
}
