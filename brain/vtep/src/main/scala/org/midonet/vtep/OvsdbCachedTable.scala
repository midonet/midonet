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

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory
import rx.schedulers.Schedulers
import rx.Observer

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.vtep.schema.Table

/**
 * A local mirror of a VTEP cache
 * This is not thread-safe, and updates should be run inside the vtep thread
 */
class OvsdbCachedTable[T <: Table, Entry <: VtepEntry](val client: OvsdbClient,
                                                       val table: T,
                                                       val vtepThread: Executor)
    extends VtepCachedTable[T, Entry] {

    case class Data(value: Entry, owner: UUID)

    private val log = LoggerFactory.getLogger(this.getClass)
    private val vtepContext = ExecutionContext.fromExecutor(vtepThread)
    private val vtepScheduler = Schedulers.from(vtepThread)

    private val monitorOwner = UUID.randomUUID()
    private var map: Map[UUID, Data] = Map()
    private val filled = Promise[Boolean]()
    private val monitor = new OvsdbTableMonitor[T, Entry](client, table)
    monitor.observable.observeOn(vtepScheduler)
        .subscribe(new Observer[VtepTableUpdate[Entry]] {
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
            case VtepEntryUpdate(_, row) =>
                map = map updated (row.uuid, Data(row, monitorOwner))
            case _ => // ignore
        }
    })

    def insert(row: Entry, hintId: UUID = UUID.randomUUID()): Unit = {
        map = map updated (row.uuid, Data(row, hintId))
        val result =
            OvsdbUtil.modify(client, table, table.insert(row, classOf[Entry]))
        result.future.onFailure {
            case e: Throwable =>
                log.warn("failed vtep table update", e)
                map.get(row.uuid) match {
                    case Some(Data(_, owner)) if owner == hintId =>
                        map = map - row.uuid
            }
        } (vtepContext)
    }

    override def get(id: UUID): Option[Entry] = map.get(id).map(_.value)
    override def getAll: Map[UUID, Entry] = map.mapValues(_.value)

    override def ready: Future[Boolean] = filled.future
    override def isReady: Boolean = filled.future.value.collect[Boolean]({
        case Success(available) => available
        case Failure(_) => false
    }).getOrElse(false)
}
