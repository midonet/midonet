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

package org.midonet.services.flowstate

import java.io.IOException
import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.google.common.annotations.VisibleForTesting
import com.typesafe.scalalogging.Logger

import org.midonet.midolman.config.FlowStateConfig
import org.midonet.util.io.stream._
import org.slf4j.LoggerFactory

package object stream {

    val LengthSize = 4

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.services.stream.flowstate-stream"))

    case class Context(config: FlowStateConfig,
                       ioManager: FlowStateManager,
                       registry: MetricRegistry)

    /**
      * Output/Input stream builders for a given port.
      */
    object ByteBufferBlockWriter {

        @VisibleForTesting
        private[flowstate] def apply(context: Context, portId: UUID)
        : ByteBufferBlockWriter[TimedBlockHeader] = {
            new ByteBufferBlockWriter[TimedBlockHeader](
                FlowStateBlock, context.ioManager.open(portId),
                context.config.expirationTime toNanos)
        }
    }

    object ByteBufferBlockReader {

        @throws[IOException]
        def apply(context: Context,
                  portId: UUID): ByteBufferBlockReader[TimedBlockHeader] = {
            new ByteBufferBlockReader[TimedBlockHeader](
                FlowStateBlock, context.ioManager.open(portId))
        }
    }
}

