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

import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.{Map => JMap}

import scala.collection.JavaConverters.mapAsJavaMapConverter

import com.sun.jna.{FunctionMapper, Library, Native, NativeLibrary}
import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

/**
  * JNA API of mido6wind native library (see mido6wind.h header for details)
  */
trait SixWind extends Library {

    /**
      * Flush the flow table (delete all)
      */
    def flush(): Unit

    /**
      * Writes a flow command (new or delete)
      *
      * @param buf a buffer with a correctly formatted Netlink OVS flow command
      * @param len the maximum len to read (usually buf.limit())
      */
    def processFlow(buf: ByteBuffer, len: Int): Unit
}

/**
  * Factory to get an instance of SixWind
  *
  * It will return a real one if the native library can be loaded, or a fake
  * one that does nothing if it cannot be loaded, emitting a warning log.
  */
object SixWind {

    private val libraryName = "mido6wind"

    private val log = Logger(LoggerFactory.getLogger("org.midonet.sixwind"))

    private def loadLibrary(): SixWind = {
        val functionMapper = new FunctionMapper {

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

        val options: JMap[String, _] = Map(
            Library.OPTION_FUNCTION_MAPPER -> functionMapper
        ).asJava

        Native.loadLibrary(libraryName,
                           classOf[SixWind],
                           options)
    }

    private def createRealLibrary(lib: SixWind): SixWind = {
        new SixWind {
            override def flush(): Unit = {
                lib.synchronized {
                    lib.flush()
                }
            }

            override def processFlow(buf: ByteBuffer, len: Int): Unit = {
                lib.synchronized {
                    lib.processFlow(buf, len)
                }
            }
        }
    }

    private def createFakeLibrary(): SixWind = {
        new SixWind {
            override def flush(): Unit = {}
            override def processFlow(buf: ByteBuffer, len: Int): Unit = {}
        }
    }

    private val instance = {
        try {
            val lib = loadLibrary()
            val sixWind = createRealLibrary(lib)
            log.info(s"6Wind DPDK support enabled")
            sixWind
        } catch {
            case e: UnsatisfiedLinkError => {
                log.info(s"6Wind DPDK support is not available on this " +
                         "platform and will be disabled")
                createFakeLibrary()
            }
        }
    }

    def create(): SixWind = {
        instance
    }
}
