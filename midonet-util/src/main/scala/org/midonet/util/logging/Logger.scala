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

import net.logstash.logback.marker.LogstashBasicMarker

import org.apache.commons.lang.StringUtils
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
    val marker: Marker =
        if (!StringUtils.isBlank(mark)) new LogstashBasicMarker(s"[$mark] ")
        else null

    // Error

    def error(message: String): Unit =
        macro LoggerMacros.errorMessage
    def error(message: String, cause: Throwable): Unit =
        macro LoggerMacros.errorMessageCause
    def error(message: String, args: AnyRef*): Unit =
        macro LoggerMacros.errorMessageArgs

    // Warn

    def warn(message: String): Unit =
        macro LoggerMacros.warnMessage
    def warn(message: String, cause: Throwable): Unit =
        macro LoggerMacros.warnMessageCause
    def warn(message: String, args: AnyRef*): Unit =
        macro LoggerMacros.warnMessageArgs

    // Info

    def info(message: String): Unit =
        macro LoggerMacros.infoMessage
    def info(message: String, cause: Throwable): Unit =
        macro LoggerMacros.infoMessageCause
    def info(message: String, args: AnyRef*): Unit =
        macro LoggerMacros.infoMessageArgs

    // Debug

    def debug(message: String): Unit =
        macro LoggerMacros.debugMessage
    def debug(message: String, cause: Throwable): Unit =
        macro LoggerMacros.debugMessageCause
    def debug(message: String, args: AnyRef*): Unit =
        macro LoggerMacros.debugMessageArgs

    // Trace

    def trace(message: String): Unit =
        macro LoggerMacros.traceMessage
    def trace(message: String, cause: Throwable): Unit =
        macro LoggerMacros.traceMessageCause
    def trace(message: String, args: AnyRef*): Unit =
        macro LoggerMacros.traceMessageArgs

}