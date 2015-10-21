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
import java.util.concurrent.{ConcurrentHashMap, Executor}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

import com.typesafe.scalalogging.Logger
import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.operations.OperationResult
import org.slf4j.LoggerFactory
import rx.Observer
import rx.schedulers.Schedulers

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.southbound.vtepTableLog
import org.midonet.southbound.vtep.OvsdbOperations.MaxBackpressureBuffer
import org.midonet.southbound.vtep.OvsdbUtil._
import org.midonet.southbound.vtep.schema.Table

/**
 * A local mirror of a VTEP cache
 *
 * This is not thread-safe, and updates should be run inside the vtep thread
 */
class OvsdbCachedTable[E <: VtepEntry](val client: OvsdbClient,
                                       val table: Table[E],
                                       val vtepExecutor: Executor,
                                       val eventExecutor: Executor) {


    private val log = Logger(LoggerFactory.getLogger(
        vtepTableLog(table.getSchema.getName)))
    private implicit val vtepContext = ExecutionContext.fromExecutor(vtepExecutor)
    private val vtepScheduler = Schedulers.from(vtepExecutor)
    private val filled = Promise[Boolean]()
    private val monitor = new OvsdbTableMonitor[E](client, table)(eventExecutor)

    protected[vtep] val entryMap = new ConcurrentHashMap[UUID, E]()

    monitor.observable
        .onBackpressureBuffer(MaxBackpressureBuffer, panicAlert(log))
        .observeOn(vtepScheduler)
        .subscribe(new Observer[VtepTableUpdate[E]] {
            override def onCompleted(): Unit = {
                log.debug("VTEP monitor closed")
                filled.tryFailure(
                    new IllegalStateException("vtep monitor closed"))
                entryMap.clear()
            }
            override def onError(e: Throwable): Unit = {
                log.warn("VTEP monitor failed", e)
                filled.tryFailure(e)
                entryMap.clear()
            }
            override def onNext(u: VtepTableUpdate[E]): Unit = u match {
                case VtepTableReady() =>
                    filled.success(true)
                case VtepEntryUpdate(row, null) if row != null =>
                    log.debug("VTEP table entry deleted: {}", row)
                    entryMap.remove(row.uuid)
                case VtepEntryUpdate(_, row) if row != null =>
                    // Data from VTEP is authoritative
                    log.debug("VTEP table entry added: {}", row)
                    entryMap.put(row.uuid, row)
                case _ => // ignore
            }
    })

    final def get(id: UUID): Option[E] = Option(entryMap.get(id))

    /** A view of all the entries */
    final def getAll: java.util.Collection[E] = entryMap.values()

    final def delete(id: UUID): Future[OperationResult] = {
        OvsdbOperations.singleOp(client, table.getDbSchema, table.deleteById(id))
    }

    final def select(id: UUID): Future[Option[E]] = {
        OvsdbOperations.singleOp(client, table.getDbSchema,
                                 table.selectById(id)) map { result =>
            if ((result.getRows ne null) && result.getRows.size() > 0) {
                Some(table.parseEntry(result.getRows.get(0)))
            } else None
        }
    }

    final def insert(row: E): Future[UUID] = {
        OvsdbOperations.singleOp(client, table.getDbSchema,
                                 table.insert(row, null)) map {
            _.getUuid.asJava
        }
    }

    final def ready: Future[Boolean] = filled.future
    final def isReady: Boolean = filled.future.value.collect[Boolean] {
        case Success(available) => available
        case Failure(_) => false
    } getOrElse false

}
