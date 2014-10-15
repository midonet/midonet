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

package org.midonet.brain.api.services

import rx.subjects.{PublishSubject, Subject}
import rx.Observable

/**
 * Rpc protocol
 */
class ProtocolHandler extends Protocol {
    private val outstream: Subject[Response, Response] = PublishSubject.create()
    override def outgoing: Observable[Response] = outstream.asObservable()
    override def onError(e: Throwable) = outstream.onCompleted()
    override def onCompleted() = outstream.onCompleted()
    override def onNext(cmd: Command) = outstream.onCompleted()
}
