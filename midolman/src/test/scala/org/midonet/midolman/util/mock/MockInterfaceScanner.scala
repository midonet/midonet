/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.midolman.util.mock

import scala.collection.mutable

import rx.subjects.PublishSubject
import rx._

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.scanner.InterfaceScanner._

class MockInterfaceScanner extends InterfaceScanner {

    private val interfaceDescriptions = mutable.Map.empty[String, InterfaceDescription]

    def addInterface(itf: InterfaceDescription): Unit = {
        interfaceDescriptions.put(itf.getName , itf)
        notificationsSubject.onNext(InterfaceUpdated(itf))
    }

    def removeInterface(name: String): Unit = {
        val itf = interfaceDescriptions.remove(name)
        if (itf.isDefined)
            notificationsSubject.onNext(InterfaceDeleted(itf.get))
    }

    private val notificationsSubject = PublishSubject.create[InterfaceOp]()

    override def subscribe(obs: Observer[InterfaceOp]): Subscription = {
        interfaceDescriptions.values map InterfaceUpdated foreach obs.onNext
        notificationsSubject.subscribe(obs)
    }

    override def close(): Unit = {}
}
