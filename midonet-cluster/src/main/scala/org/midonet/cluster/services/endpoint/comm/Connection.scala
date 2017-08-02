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

import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try

import rx.Observer

/**
 * Connection state holder.
 * This class represents a connection and implements an observer
 * for the messages that are to be sent back to the client.
 * It receives its corresponding connection manager, so it is
 * able to unregister on termination.
 */
trait Connection[ReqType, RespType] extends Observer[RespType] {
    /** Process a newly received message from a client */
    def msg(request: ReqType): Unit
    /** Process an error captured in the low-level connection */
    def error(e: Throwable): Unit
    /** Dismiss the connection state */
    def disconnect(): Unit
}

trait ConnectionFactory[Discriminator, ReqType, RespType] {
    def get(ctx: Discriminator)
           (implicit mgr: ConnectionManager[Discriminator, ReqType, RespType])
        : Connection[ReqType, RespType]
}

/**
 * A class to keep a set of connections associated to connection contexts
 */
class ConnectionManager[Discriminator, ReqType, RespType]
    (factory: ConnectionFactory[Discriminator, ReqType, RespType]) {

    // The connection manager has been disposed of
    private val disposed = new AtomicBoolean(false)

    private val channels =
        TrieMap[Discriminator, Future[Connection[ReqType, RespType]]]()

    implicit val mgr: ConnectionManager[Discriminator, ReqType, RespType] = this

    /** Register and/or retrieve connection status associated to a context */
    def get(ctx: Discriminator): Option[Connection[ReqType, RespType]] =
        if (!disposed.get()) {
            val conn = Promise[Connection[ReqType, RespType]]()
            channels.putIfAbsent(ctx, conn.future) match {
                case Some(previous) =>
                    Some(Await.result(previous, Duration.Inf))
                case None =>
                    Some(Await.result(conn.complete(
                        Try(factory.get(ctx))).future, Duration.Inf))
            }
        } else {
            None
        }

    /** Forget about the connection status associated to a context */
    def forget(ctx: Discriminator): Unit = channels.remove(ctx)

    /** Dispose of all existing connections */
    def dispose(): Unit = {
        if (disposed.compareAndSet(false, true))
            channels.foreach(e => {
                Await.result(e._2, Duration.Inf).disconnect()
                forget(e._1)
            })
    }
}

