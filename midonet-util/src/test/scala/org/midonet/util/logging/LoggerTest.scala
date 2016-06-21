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

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}
import org.scalatest.junit.JUnitRunner
import org.slf4j.{Marker, Logger => Underlying}

@RunWith(classOf[JUnitRunner])
class LoggerTest extends FlatSpec with Matchers with GivenWhenThen {

    "Logger without mark" should "log messages" in {
        Given("An underlying logger")
        val underlying = Mockito.mock(classOf[Underlying])

        Mockito.when(underlying.isErrorEnabled).thenReturn(true)
        Mockito.when(underlying.isWarnEnabled).thenReturn(true)
        Mockito.when(underlying.isInfoEnabled).thenReturn(true)
        Mockito.when(underlying.isDebugEnabled).thenReturn(true)
        Mockito.when(underlying.isTraceEnabled).thenReturn(true)

        And("A logger wrapper")
        val log = Logger(underlying)
        val message = "message"
        val throwable = new Throwable
        val any = new AnyRef

        When("Logging a message")
        log.error(message)
        log.error(message, throwable)
        log.error(message, any)

        log.warn(message)
        log.warn(message, throwable)
        log.warn(message, any)

        log.info(message)
        log.info(message, throwable)
        log.info(message, any)

        log.debug(message)
        log.debug(message, throwable)
        log.debug(message, any)

        log.trace(message)
        log.trace(message, throwable)
        log.trace(message, any)

        Then("The logger should call the underlying logger")
        Mockito.verify(underlying).error(null.asInstanceOf[Marker], message)
        Mockito.verify(underlying).error(null.asInstanceOf[Marker], message,
                                         throwable)
        Mockito.verify(underlying).error(null.asInstanceOf[Marker], message,
                                         any)

        Mockito.verify(underlying).warn(null.asInstanceOf[Marker], message)
        Mockito.verify(underlying).warn(null.asInstanceOf[Marker], message,
                                        throwable)
        Mockito.verify(underlying).warn(null.asInstanceOf[Marker], message,
                                        any)

        Mockito.verify(underlying).info(null.asInstanceOf[Marker], message)
        Mockito.verify(underlying).info(null.asInstanceOf[Marker], message,
                                        throwable)
        Mockito.verify(underlying).info(null.asInstanceOf[Marker], message,
                                        any)

        Mockito.verify(underlying).debug(null.asInstanceOf[Marker], message)
        Mockito.verify(underlying).debug(null.asInstanceOf[Marker], message,
                                         throwable)
        Mockito.verify(underlying).debug(null.asInstanceOf[Marker], message,
                                         any)

        Mockito.verify(underlying).trace(null.asInstanceOf[Marker], message)
        Mockito.verify(underlying).trace(null.asInstanceOf[Marker], message,
                                         throwable)
        Mockito.verify(underlying).trace(null.asInstanceOf[Marker], message,
                                         any)
    }

    "Logger with mark" should "log messages" in {
        Given("An underlying logger")
        val underlying = Mockito.mock(classOf[Underlying])

        Mockito.when(underlying.isErrorEnabled).thenReturn(true)
        Mockito.when(underlying.isWarnEnabled).thenReturn(true)
        Mockito.when(underlying.isInfoEnabled).thenReturn(true)
        Mockito.when(underlying.isDebugEnabled).thenReturn(true)
        Mockito.when(underlying.isTraceEnabled).thenReturn(true)

        And("A logger wrapper")
        val mark = "mark"
        val log = Logger(underlying, mark)
        val message = "message"
        val throwable = new Throwable
        val any = new AnyRef

        When("Logging a message")
        log.error(message)
        log.error(message, throwable)
        log.error(message, any)

        log.warn(message)
        log.warn(message, throwable)
        log.warn(message, any)

        log.info(message)
        log.info(message, throwable)
        log.info(message, any)

        log.debug(message)
        log.debug(message, throwable)
        log.debug(message, any)

        log.trace(message)
        log.trace(message, throwable)
        log.trace(message, any)

        Then("The logger should call the underlying logger")
        Mockito.verify(underlying).error(log.marker, message)
        Mockito.verify(underlying).error(log.marker, message, throwable)
        Mockito.verify(underlying).error(log.marker, message, any)

        Mockito.verify(underlying).warn(log.marker, message)
        Mockito.verify(underlying).warn(log.marker, message, throwable)
        Mockito.verify(underlying).warn(log.marker, message, any)

        Mockito.verify(underlying).info(log.marker, message)
        Mockito.verify(underlying).info(log.marker, message, throwable)
        Mockito.verify(underlying).info(log.marker, message, any)

        Mockito.verify(underlying).debug(log.marker, message)
        Mockito.verify(underlying).debug(log.marker, message, throwable)
        Mockito.verify(underlying).debug(log.marker, message, any)

        Mockito.verify(underlying).trace(log.marker, message)
        Mockito.verify(underlying).trace(log.marker, message, throwable)
        Mockito.verify(underlying).trace(log.marker, message, any)
    }
}
