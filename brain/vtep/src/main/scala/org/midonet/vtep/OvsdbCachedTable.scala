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

package org.midonet.vtep

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.vtep.schema.Table

/**
 * A local mirror of a VTEP cache
 */
class OvsdbCachedTable[T <: Table, Entry <: VtepEntry](val client: OvsdbClient,
                                                       val table: T)
    extends VtepCachedTable[T, Entry] {

    private val log = LoggerFactory.getLogger(this.getClass)

    private var map: Map[UUID, Entry] = Map()
    private val filled = Promise[Boolean]()
    private val monitor = new OvsdbTableMonitor[T, Entry](client, table)
    monitor.observable.subscribe(new Observer[VtepTableUpdate[Entry]] {
        override def onCompleted(): Unit = {
            log.debug("vtep monitor closed")
            filled.tryFailure(new IllegalStateException("vtep monitor closed"))
            map = Map()
        }
        override def onError(e: Throwable): Unit = {
            log.error("vtep monitor failed", e)
            filled.tryFailure(e)
            map = Map()
        }
        override def onNext(u: VtepTableUpdate[Entry]): Unit = u match {
            case VtepTableReady() => filled.success(true)
            case VtepEntryUpdate(null, null) => // ignore
            case VtepEntryUpdate(row, null) => map = map - row.uuid
            case VtepEntryUpdate(_, row) => map = map updated (row.uuid, row)
            case _ => // ignore
        }
    })

    def get(id: UUID): Option[Entry] = map.get(id)
    def getAll: Map[UUID, Entry] = map

    def ready: Future[Boolean] = filled.future
    def isReady: Boolean = filled.future.value.collect[Boolean]({
        case Success(available) => available
        case Failure(_) => false
    }).getOrElse(false)
}
