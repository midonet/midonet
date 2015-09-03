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

package org.midonet.southbound.vtep

import java.util.UUID
import java.util.concurrent.Executor

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.slf4j.LoggerFactory
import rx.Observer
import rx.schedulers.Schedulers

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.southbound.vtep.schema.Table
import org.midonet.southbound.vtep.OvsdbVtepData.panicAlert
import org.midonet.southbound.vtep.OvsdbUtil._

/**
 * A local mirror of a VTEP cache
 *
 * This is not thread-safe, and updates should be run inside the vtep thread
 */
class OvsdbCachedTable[T <: Table, Entry <: VtepEntry](val client: OvsdbClient,
                                                       val table: T,
                                                       val clazz: Class[Entry],
                                                       val vtepExecutor: Executor,
                                                       val eventExecutor: Executor)
    extends VtepCachedTable[T, Entry] {

    private val MaxBackpressureBuffer = 100000

    case class Data(value: Entry, owner: UUID)

    private val log = Logger(LoggerFactory.getLogger(
        s"org.midonet.vtep.table-[${table.getSchema.getName}]"))
    private implicit val vtepContext = ExecutionContext.fromExecutor(vtepExecutor)
    private val vtepScheduler = Schedulers.from(vtepExecutor)

    private var map: Map[UUID, List[Data]] = Map()
    private val filled = Promise[Boolean]()
    private val monitor =
        new OvsdbTableMonitor[T, Entry](client, table, clazz, eventExecutor)

    monitor.observable
        .onBackpressureBuffer(MaxBackpressureBuffer, panicAlert(log))
        .observeOn(vtepScheduler)
        .subscribe(new Observer[VtepTableUpdate[Entry]] {
            override def onCompleted(): Unit = {
                log.debug("VTEP monitor closed")
                filled.tryFailure(
                    new IllegalStateException("vtep monitor closed"))
                map = Map.empty
            }
            override def onError(e: Throwable): Unit = {
                log.error("VTEP monitor failed", e)
                filled.tryFailure(e)
                map = Map.empty
            }
            override def onNext(u: VtepTableUpdate[Entry]): Unit = u match {
                case VtepTableReady() =>
                    filled.success(true)
                case VtepEntryUpdate(row, null) if row != null =>
                    log.debug("VTEP table entry deleted: {}", row)
                    map = map - row.uuid
                case VtepEntryUpdate(_, row) if row != null =>
                    // Data from VTEP is authoritative
                    log.debug("VTEP table entry added: {}", row)
                    map = map updated (row.uuid, List(Data(row, null)))
                case _ => // ignore
            }
    })

    override def select(id: UUID): Future[Option[Entry]] = {
        OvsdbOperations.singleOp(client, table.getDbSchema, table.selectById(id),
                                 vtepExecutor) map { result =>
            if ((result.getRows ne null) && result.getRows.size() > 0) {
                Some(table.parseEntry(result.getRows.get(0), clazz))
            } else None
        }
    }

    override def insert(row: Entry): Future[Entry] = {
        OvsdbOperations.singleOp(client, table.getDbSchema, table.insert(row),
                                 vtepExecutor) map {
            _.getUuid.asJava
        } flatMap { select } map { _.get }
    }

    override def get(id: UUID): Option[Entry] = map.get(id).map(_.head.value)
    override def getAll: Map[UUID, Entry] = map.mapValues(_.head.value)

    override def ready: Future[Boolean] = filled.future
    override def isReady: Boolean = filled.future.value.collect[Boolean]({
        case Success(available) => available
        case Failure(_) => false
    }).getOrElse(false)
}
