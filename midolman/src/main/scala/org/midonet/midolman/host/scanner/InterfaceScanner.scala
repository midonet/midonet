/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.host.scanner

import org.midonet.midolman.host.scanner.InterfaceScanner.InterfaceOp
import rx.{Observer, Subscription}

import org.midonet.midolman.host.interfaces.InterfaceDescription

object InterfaceScanner {
    sealed trait InterfaceOp {
        val desc: InterfaceDescription
    }
    case object NoOp extends InterfaceOp {
        val desc = null
    }
    case class InterfaceUpdated(desc: InterfaceDescription) extends InterfaceOp
    case class InterfaceDeleted(desc: InterfaceDescription) extends InterfaceOp
}
/**
 * This class listens for interface changes and propagates them
 * to the subscribers.
 */
trait InterfaceScanner {
    def subscribe(obs: Observer[InterfaceOp]): Subscription
    def close(): Unit
}
