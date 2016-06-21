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

import scala.reflect.macros.blackbox

private object LoggerMacros {

    type LoggerContext = blackbox.Context { type PrefixType = Logger }

    def errorMessage(c: LoggerContext)(message: c.Expr[String]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.error($marker, $message)"
    }

    def errorMessageCause(c: LoggerContext)(message: c.Expr[String],
                                            cause: c.Expr[Throwable]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.error($marker, $message, $cause)"
    }

    def errorMessageArgs(c: LoggerContext)(message: c.Expr[String],
                                           args: c.Expr[AnyRef]*) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.error($marker, $message, ..$args)"
    }

    def warnMessage(c: LoggerContext)(message: c.Expr[String]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.warn($marker, $message)"
    }

    def warnMessageCause(c: LoggerContext)(message: c.Expr[String],
                                           cause: c.Expr[Throwable]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.warn($marker, $message, $cause)"
    }

    def warnMessageArgs(c: LoggerContext)(message: c.Expr[String],
                                          args: c.Expr[AnyRef]*) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.warn($marker, $message, ..$args)"
    }

    def infoMessage(c: LoggerContext)(message: c.Expr[String]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.info($marker, $message)"
    }

    def infoMessageCause(c: LoggerContext)(message: c.Expr[String],
                                           cause: c.Expr[Throwable]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.info($marker, $message, $cause)"
    }

    def infoMessageArgs(c: LoggerContext)(message: c.Expr[String],
                                          args: c.Expr[AnyRef]*) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.info($marker, $message, ..$args)"
    }

    def debugMessage(c: LoggerContext)(message: c.Expr[String]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.debug($marker, $message)"
    }

    def debugMessageCause(c: LoggerContext)(message: c.Expr[String],
                                            cause: c.Expr[Throwable]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.debug($marker, $message, $cause)"
    }

    def debugMessageArgs(c: LoggerContext)(message: c.Expr[String],
                                           args: c.Expr[AnyRef]*) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.debug($marker, $message, ..$args)"
    }

    def traceMessage(c: LoggerContext)(message: c.Expr[String]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.trace($marker, $message)"
    }

    def traceMessageCause(c: LoggerContext)(message: c.Expr[String],
                                            cause: c.Expr[Throwable]) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.trace($marker, $message, $cause)"
    }

    def traceMessageArgs(c: LoggerContext)(message: c.Expr[String],
                                           args: c.Expr[AnyRef]*) = {
        import c.universe._
        val wrapper = q"${c.prefix}.wrapper"
        val marker = q"${c.prefix}.marker"
        q"$wrapper.trace($marker, $message, ..$args)"
    }
}
