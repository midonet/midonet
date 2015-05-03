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
import java.util.concurrent.Executor

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory
import rx.Observer
import rx.schedulers.Schedulers

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.vtep.schema.Table

/**
 * A local mirror of a VTEP cache
 * This is not thread-safe, and updates should be run inside the vtep thread
 */
class OvsdbCachedTable[T <: Table, Entry <: VtepEntry](val client: OvsdbClient,
                                                       val table: T,
                                                       val clazz: Class[Entry],
                                                       val vtepThread: Executor)
    extends VtepCachedTable[T, Entry] {

    case class Data(value: Entry, owner: UUID)

    private val log = LoggerFactory.getLogger(this.getClass)
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val vtepScheduler = Schedulers.from(vtepThread)

    private var map: Map[UUID, List[Data]] = Map()
    private val filled = Promise[Boolean]()
    private val monitor = new OvsdbTableMonitor[T, Entry](client, table, clazz)
    monitor.observable.onBackpressureBuffer().observeOn(vtepScheduler)
        .subscribe(new Observer[VtepTableUpdate[Entry]] {
        override def onCompleted(): Unit = {
            log.debug("vtep monitor closed")
            filled.tryFailure(new IllegalStateException("vtep monitor closed"))
            map = Map.empty
        }
        override def onError(e: Throwable): Unit = {
            log.error("vtep monitor failed", e)
            filled.tryFailure(e)
            map = Map.empty
        }
        override def onNext(u: VtepTableUpdate[Entry]): Unit = u match {
            case VtepTableReady() => filled.success(true)
            case VtepEntryUpdate(row, null) if row != null =>
                map = map - row.uuid
            case VtepEntryUpdate(_, row) if row != null =>
                // Data from VTEP is authoritative
                map = map updated (row.uuid, List(Data(row, null)))
            case _ => // ignore
        }
    })

    /** NOTE: this is not thread safe: it must be executed by the same thread
      * monitoring the vtep table */
    override def insert(row: Entry): Future[UUID] = {
        val result = Promise[UUID]()
        val hint = insertHint(row)
        OvsdbTools.insert(client, table, table.insert(row))
            .future.onComplete {
            case Failure(exc) =>
                removeHint(row.uuid, hint)
                result.failure(exc)
            case Success(uuid) =>
                result.success(uuid)
        }(vtepContext)
        result.future
    }

    def insertHint(row: Entry, hint: UUID = UUID.randomUUID()): UUID = {
        map = map updated(row.uuid,
            Data(row, hint) :: map.getOrElse(row.uuid, Nil))
        hint
    }

    def removeHint(rowId: UUID, hint: UUID): Unit =
        map.getOrElse(rowId, Nil).filterNot(_.owner == hint) match {
            case Nil => map = map - rowId
            case entries => map = map updated (rowId, entries)
        }

    override def get(id: UUID): Option[Entry] = map.get(id).map(_.head.value)
    override def getAll: Map[UUID, Entry] = map.mapValues(_.head.value)

    override def ready: Future[Boolean] = filled.future
    override def isReady: Boolean = filled.future.value.collect[Boolean]({
        case Success(available) => available
        case Failure(_) => false
    }).getOrElse(false)
}
