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

import scala.concurrent.{Future, Promise}

import io.netty.channel.{Channel, ChannelFuture, ChannelFutureListener}

object ChannelUtil {

    implicit def toWrapper(f: ChannelFuture): ChannelFutureWrapper = {
        new ChannelFutureWrapper(f)
    }

    class ChannelFutureWrapper(val f: ChannelFuture) extends AnyVal {

        /**
          * @return Wraps the current [[ChannelFuture]] as a Scala [[Future]]
          *         that completes with the [[Channel]].
          */
        def asScala: Future[Channel] = {
            val promise = Promise[Channel]()
            f.addListener(new ChannelFutureListener {
                override def operationComplete(future: ChannelFuture): Unit = {
                    if (future.isSuccess) promise trySuccess future.channel()
                    else promise tryFailure future.cause()
                }
            })
            promise.future
        }
    }

}
