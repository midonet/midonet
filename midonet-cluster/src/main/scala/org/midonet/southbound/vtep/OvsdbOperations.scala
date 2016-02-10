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

import java.util.concurrent.{Executor, TimeUnit}
import java.util.{Collections, List => JList}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}

import com.google.common.util.concurrent.{FutureCallback, Futures}

import io.netty.channel.{Channel, ChannelFuture, ChannelFutureListener}
import io.netty.util.concurrent
import io.netty.util.concurrent.GenericFutureListener

import org.opendaylight.ovsdb.lib.message._
import org.opendaylight.ovsdb.lib.notation.{Condition, Row}
import org.opendaylight.ovsdb.lib.operations.{OperationResult, Select}
import org.opendaylight.ovsdb.lib.schema.{ColumnSchema, DatabaseSchema, GenericTableSchema}
import org.opendaylight.ovsdb.lib.{MonitorCallBack, OvsdbClient}

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.vtep.VtepException
import org.midonet.southbound.vtep.OvsdbTools.opResultHasError
import org.midonet.southbound.vtep.schema.Table
import org.midonet.southbound.vtep.schema.Table.OvsdbSelect

object OvsdbOperations {

    final val DbHardwareVtep = "hardware_vtep"
    final val VoidCloseFuture = new ChannelFuture {

        type FutureListener = GenericFutureListener[_ <: concurrent.Future[_ >: Void]]
        override def isVoid(): Boolean = true
        override def sync(): ChannelFuture = this
        override def await(): ChannelFuture = this
        override def addListener(listener: FutureListener): ChannelFuture = this
        override def addListeners(listeners: FutureListener*): ChannelFuture = this
        override def removeListener(listener: FutureListener): ChannelFuture = this
        override def removeListeners(listeners: FutureListener*): ChannelFuture = this
        override def syncUninterruptibly(): ChannelFuture = this
        override def awaitUninterruptibly(): ChannelFuture = this
        override def channel(): Channel = null
        override def await(timeout: Long, unit: TimeUnit): Boolean = false
        override def await(timeoutMillis: Long): Boolean = false
        override def isCancellable: Boolean = false
        override def cancel(mayInterruptIfRunning: Boolean): Boolean = false
        override def cause(): Throwable = null
        override def isSuccess: Boolean = false
        override def getNow: Void = null
        override def awaitUninterruptibly(timeout: Long,
                                          unit: TimeUnit): Boolean = false
        override def awaitUninterruptibly(timeoutMillis: Long): Boolean = false
        override def isCancelled: Boolean = false
        override def get(): Void = null
        override def get(timeout: Long, unit: TimeUnit): Void = null
        override def isDone: Boolean = false
    }

    final val GreedyMonitorSelect = new MonitorSelect(true, true, true, true)
    final val MaxBackpressureBuffer = 100000

    /**
     * Gets the database schema from the OVSDB-based VTEP asynchronously. The
     * method returns a future which completes either when the OVSDB operation
     * completes or when the connection channel closes.
     */
    def getDbSchema(client: OvsdbClient, dbName: String,
                    closeFuture: ChannelFuture = VoidCloseFuture,
                    closeException: Exception = null)
                   (implicit executor: Executor)
    : Future[DatabaseSchema] = {
        implicit val ec = ExecutionContext.fromExecutor(executor)
        asFuture[JList[String]](client, closeFuture, closeException) {
            Futures.addCallback(client.getDatabases, _, executor)
        } flatMap { dbList =>
            if (!dbList.contains(dbName)) {
                throw new OvsdbNotFoundException(client,
                                                 s"Database not found $dbName")
            }

            asFuture[DatabaseSchema](client, closeFuture, closeException) {
                Futures.addCallback(client.getSchema(dbName), _, executor)
            }
        } recoverWith { wrapException(client) }
    }

    /**
     * Executes a single operation transaction against the OVSDB-based VTEP.
     */
    def singleOp(client: OvsdbClient, schema: DatabaseSchema,
                 op: Table.OvsdbOperation,
                 closeFuture: ChannelFuture = VoidCloseFuture,
                 closeException: Exception = null)
                (implicit executor: Executor)
    : Future[OperationResult] = {
        implicit val ec = ExecutionContext.fromExecutor(executor)
        multiOp(client, schema, Seq(op), closeFuture, closeException) map {
            results => results.head
        }
    }

    /**
     * Executes a multi-operation transaction against the OVSDB-based VTEP.
     */
    def multiOp(client: OvsdbClient, schema: DatabaseSchema,
                ops: Seq[Table.OvsdbOperation],
                closeFuture: ChannelFuture = VoidCloseFuture,
                closeException: Exception = null)
               (implicit executor: Executor)
    : Future[Seq[OperationResult]] = {
        implicit val ec = ExecutionContext.fromExecutor(executor)
        val transaction = client.transactBuilder(schema)
        for (op <- ops) {
            transaction.add(op.op)
        }

        asFuture[JList[OperationResult]](client, closeFuture, closeException) {
            Futures.addCallback(transaction.execute(), _, executor)
        } map { results =>
            for (result <- results.asScala if opResultHasError(result)) {
                throw new OvsdbOpException(client, ops.asJava,
                                           results.indexOf(result), result)
            }
            results.asScala
        }
    }

    /**
     * Gets all entries for the specified table, columns and condition.
     */
    def tableEntries(client: OvsdbClient,
                     schema: DatabaseSchema,
                     table: GenericTableSchema,
                     columns: Seq[ColumnSchema[GenericTableSchema, _]],
                     condition: Condition)
                    (implicit executor: Executor)
    : Future[Seq[Row[GenericTableSchema]]] = {
        implicit val ec = ExecutionContext.fromExecutor(executor)
        val op = new Select(table)

        for (column <- columns) {
            op.column(column)
        }
        if (condition ne null) {
            op.addCondition(condition)
        }

        singleOp(client, schema, new OvsdbSelect(op)) map { result =>
            result.getRows.asScala
        }
    }

    /**
     * Returns an observable that, when subscribed to, emits notifications for
     * any change to the give table and columns.
     */
    def tableUpdates(client: OvsdbClient,
                     schema: DatabaseSchema,
                     table: GenericTableSchema,
                     columns: Seq[ColumnSchema[GenericTableSchema, _]])
    : Observable[TableUpdate[GenericTableSchema]] = {

        type Request = MonitorRequest[GenericTableSchema]
        type Update = TableUpdate[GenericTableSchema]

        val request = MonitorRequestBuilder
            .builder(table)
            .`with`(GreedyMonitorSelect)
            .addColumns(columns.asJava)
            .build()

        val onSubscribe = new OnSubscribe[Update] {
            override def call(child: Subscriber[_ >: Update]): Unit = {
                val callback = new MonitorCallBack {
                    override def update(result: TableUpdates,
                                        dbSchema: DatabaseSchema): Unit = {
                        val update = result.getUpdate(table)
                        if (update ne null) {
                            child onNext update
                        }
                    }

                    override def exception(t: Throwable): Unit = {
                        child onError new OvsdbException(client, t)
                    }
                }
                // TODO: Use the monitor handle to cancel the monitor when
                // TODO: unsubscribing. Currently, this is not possible because
                // TODO: the `monitor` method does not return the handle.
                client.monitor(schema,
                               Collections.singletonList[Request](request),
                               callback)
            }
        }
        Observable.create(onSubscribe)
    }

    /** Converts an asynchronous call to the OVSDB database to a [[Future]]. */
    private def asFuture[T](client: OvsdbClient, closeFuture: ChannelFuture,
                            closeException: Exception)
                           (f: (FutureCallback[T]) => Unit)
                           (implicit ec: ExecutionContext): Future[T] = {
        val promise = Promise[T]()

        val channelListener = new ChannelFutureListener {
            override def operationComplete(future: ChannelFuture): Unit = {
                closeFuture removeListener this
                promise tryFailure closeException
            }
        }
        closeFuture addListener channelListener

        val callback = new FutureCallback[T] {
            override def onSuccess(t: T): Unit = {
                closeFuture removeListener channelListener
                promise trySuccess t
            }
            override def onFailure(t: Throwable): Unit = {
                closeFuture removeListener channelListener
                promise tryFailure new OvsdbException(client, t)
            }
        }

        f(callback)

        promise.future
    }

    private def wrapException[T](client: OvsdbClient)
    : PartialFunction[Throwable, Future[T]] = {
        case e: VtepException => Future.failed(e)
        case t => Future.failed(new OvsdbException(client, t))
    }

}
