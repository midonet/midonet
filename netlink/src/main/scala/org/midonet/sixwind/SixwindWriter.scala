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
package org.midonet.sixwind

import collection.JavaConverters.mapAsJavaMapConverter
import com.sun.jna.{FunctionMapper, Library, Native, NativeLibrary}
import com.typesafe.scalalogging.Logger
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.{Map => JMap}
import org.slf4j.LoggerFactory

/**
 * API for accessing mido6wind native library using JNA
 */
trait Mido6Wind extends Library {
    def processFlow(buf: ByteBuffer, len: Int): Int
    def flush(): Int
}

/**
 * Singleton to bootstrap (construct + flush) a SixwindWriter
 */
object SixwindWriter {
    def bootstrap(): SixwindWriter = {
        val sixwindWriter = new SixwindWriter()
        sixwindWriter.flush()
        sixwindWriter
    }
}

/**
 * Utility class to "write" flows using native 6wind API.
 */
class SixwindWriter {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.sixwind"))

    private val functionMapper = new FunctionMapper {

        private val functionMap = collection.immutable.HashMap(
            "processFlow" -> "mido6wind_process_flow",
            "flush" -> "mido6wind_flush"
        )

        override def getFunctionName(library: NativeLibrary,
                                     method: Method)
        : String = {
            functionMap.getOrElse(method.getName, method.getName)
        }
    }

    private val cLib: Option[Mido6Wind] = {
        try {
            val options: JMap[_,_] = collection.mutable.HashMap(
                Library.OPTION_FUNCTION_MAPPER -> functionMapper
            ).asJava

            Option(Native.loadLibrary("mido6wind",
                                      classOf[Mido6Wind],
                                      options).asInstanceOf[Mido6Wind])
        } catch {
            case e: UnsatisfiedLinkError => {
                log.error(e.getMessage, e)
                None
            }
        }
    }

    /**
     * Check if the writer is ready. The result never changes.
     *
     * @return true if the native library has been loaded successfully
     */
    def isReady: Boolean = {
        cLib.isDefined
    }

    /**
     * Writes a flow command (new or delete)
     *
     * @param src a buffer with a correctly formatted Netlink OVS flow command
     * @return if isReady() returns true, the result code, else it returns 0
     */
    def write(src: ByteBuffer): Int = {
        cLib match {
            case Some(instance) => {
                var result: Int = 0
                instance.synchronized {
                    result = instance.processFlow(src, src.limit())
                }
                if(result != 0) {
                    log.error(s"processFlow returned an error (code: $result)")
                }
                result
            }
            case None => 0
        }
    }

    /**
     * Flush the flow table (delete all)
     *
     * @return if isReady() returns true, the result code, else it returns 0
     */
    def flush(): Int = {
        cLib match {
            case Some(instance) => {
                var result: Int = 0
                instance.synchronized {
                    result = instance.flush()
                }
                if(result != 0) {
                    log.error(s"flush returned an error (code: $result)")
                }
                result
            }
            case None => 0
        }
    }
}
