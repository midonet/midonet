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

import java.nio.ByteBuffer
import com.sun.jna.Library
import com.sun.jna.Native
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

trait Mido6Wind extends Library {
    def process_flow(buf: ByteBuffer, len: Int): Int
}

/**
  * Utility class to write Netlink messages to native FastPath API.
  */
class SixwindWriter() {

    private val log = Logger(LoggerFactory.getLogger("org.midonet.sixwind"))

    private val cLib : Option[Mido6Wind] = {
        try {
            Option(Native.loadLibrary("mido6wind", classOf[Mido6Wind]).asInstanceOf[Mido6Wind])
        } catch {
            case e: UnsatisfiedLinkError => {
                log.error(e.getMessage, e)
                None
            }
        }
    }

    /**
      * Check if the writer is ready. The result never changes.
      * @return true if the native library has been loaded successfully
      */
    def isReady(): Boolean = {
        cLib match {
            case Some(x) => true
            case None => false
        }
    }

    /**
      * Writes from the source buffer into the the channel. Returns the amount
      * of bytes written. We assume the buffers contains one or more correctly
      * formatted Netlink messages
      * @return if isReady() returns true, the result code, else it returns 0
      */
    def write(src: ByteBuffer): Int = {
        cLib match {
            case Some(x) => {
                val result = x.process_flow(src, src.limit())
                if(result != 0) {
                    log.error(s"process_flow returned an error (code: $result)")
                }
                result
            }
            case None => 0
        }
    }
}