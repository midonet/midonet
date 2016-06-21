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

package org.midonet.util.logging

import scala.language.experimental.macros

import com.typesafe.scalalogging.{Logger => Wrapper}

import org.apache.commons.lang.StringUtils
import org.slf4j.helpers.LogstashBasicMarker
import org.slf4j.{Marker, Logger => Underlying}

/**
  * Companion for [[Logger]], providing a factory for [[Logger]]s.
  */
object Logger {

    /**
      * Create a [[Logger]] wrapping the given underlying
      * [[com.typesafe.scalalogging.Logger]].
      */
    def apply(underlying: Underlying): Logger =
        new Logger(underlying, null)

    /**
      * Create a [[Logger]] wrapping the given underlying
      * [[com.typesafe.scalalogging.Logger]] with a specified marker.
      */
    def apply(underlying: Underlying, mark: String): Logger =
        new Logger(underlying, mark)

}

final class Logger private (val underlying: Underlying, mark: String) {

    val wrapper = Wrapper(underlying)
    private[logging] val marker: Marker =
        if (!StringUtils.isBlank(mark)) new LogstashBasicMarker(s"[$mark] ")
        else null

    // Error

    @inline def error(message: String): Unit =
        wrapper.error(marker, message)
    @inline def error(message: String, cause: Throwable): Unit =
        wrapper.error(marker, message, cause)
    @inline def error(message: String, args: AnyRef*): Unit =
        wrapper.error(marker, message, args: _*)

    // Warn

    @inline def warn(message: String): Unit =
        wrapper.warn(marker, message)
    @inline def warn(message: String, cause: Throwable): Unit =
        wrapper.warn(marker, message, cause)
    @inline def warn(message: String, args: AnyRef*): Unit =
        wrapper.warn(marker, message, args: _*)

    // Info

    @inline def info(message: String): Unit =
        wrapper.info(marker, message)
    @inline def info(message: String, cause: Throwable): Unit =
        wrapper.info(marker, message, cause)
    @inline def info(message: String, args: AnyRef*): Unit =
        wrapper.info(marker, message, args: _*)

    // Debug

    @inline def debug(message: String): Unit =
        wrapper.debug(marker, message)
    @inline def debug(message: String, cause: Throwable): Unit =
        wrapper.debug(marker, message, cause)
    @inline def debug(message: String, args: AnyRef*): Unit =
        wrapper.debug(marker, message, args: _*)

    // Trace

    @inline def trace(message: String): Unit =
        wrapper.trace(marker, message)
    @inline def trace(message: String, cause: Throwable): Unit =
        wrapper.trace(marker, message, cause)
    @inline def trace(message: String, args: AnyRef*): Unit =
        wrapper.trace(marker, message, args: _*)

}