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

package org.midonet.cluster.services.state

import java.net.SocketAddress
import java.util
import java.util.Collections
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, ThreadFactory}

import scala.collection.breakOut
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future, TimeoutException}

import com.typesafe.scalalogging.Logger

import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.rpc.State.ProxyRequest.{Subscribe, Unsubscribe}
import org.midonet.cluster.rpc.State.ProxyResponse.Error.Code
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.state.StateTableManager.State
import org.midonet.cluster.services.state.server.{ClientContext, ClientHandler, ClientUnregisteredException}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.CallingThreadExecutionContext

object StateTableManager {

    private type ContextMap = util.Map[SocketAddress, ClientContext]
    private type ContextHashMap = util.HashMap[SocketAddress, ClientContext]

    private class State(val closed: Boolean, contexts: ContextMap) {

        def add(address: SocketAddress, context: ClientContext): State = {
            val newContexts = new ContextHashMap(contexts)
            newContexts.put(address, context)
            new State(closed, newContexts)
        }

        def remove(address: SocketAddress): State = {
            if (contexts.size() == 1 && contexts.containsKey(address)) {
                if (closed) State.Closed else State.Init
            } else if (contexts.size() == 0) {
                this
            } else {
                val newContexts = new ContextHashMap(contexts)
                newContexts.remove(address)
                new State(closed, newContexts)
            }
        }

        def get(address: SocketAddress): ClientContext = {
            contexts.get(address)
        }

        def list: util.Collection[ClientContext] = {
            contexts.values()
        }
    }

    private object State {
        val Init = new State(closed = false, Collections.emptyMap())
        val Closed = new State(closed = true, Collections.emptyMap())
    }

}

/**
  * Implements the server side of the State-Proxy protocol.
  */
class StateTableManager(config: StateProxyConfig, backend: MidonetBackend) {

    protected[state] val log = Logger(LoggerFactory.getLogger(StateProxyLog))

    private val caches = new ConcurrentHashMap[StateTableKey, StateTableCache]()
    private val state = new AtomicReference[State](State.Init)
    private val subscriptionCounter = new AtomicLong()

    private val threadCount =
        if (config.cacheThreads > 0) config.cacheThreads
        else Integer.min(Runtime.getRuntime.availableProcessors(), 4)
    private val executors = new Array[ScheduledExecutorService](threadCount)
    private val executorIndex = new AtomicLong()

    for (index <- 0 until threadCount) {
        executors(index) = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory {
                override def newThread(runnable: Runnable): Thread = {
                    val thread = new Thread(runnable, s"state-proxy-cache-$index")
                    thread.setDaemon(true)
                    thread
                }
            })
    }

    /**
      * Closes the manager. This is a graceful shutdown and releases all
      * resources for the current subscriptions. It also ensures that current
      * clients receive a notification that the manager is shutting down.
      */
    def close(): Unit = {
        val contexts = state.getAndSet(State.Closed).list

        val futures = for (context <- contexts.asScala) yield {
            context.close(shutdown = true)
        }

        try Await.ready(Future.sequence(futures)(breakOut,
                                                 CallingThreadExecutionContext),
                        config.serverShutdownTimeout)
        catch {
            case e: TimeoutException =>
                log warn "Failed to gracefully close all clients within " +
                         s"${config.serverShutdownTimeout.toMillis} milliseconds"
        }

        for (cache <- caches.values().asScala) {
            cache.close()
        }
    }

    /**
      * Registers a new client handler for the specified client address.
      */
    @throws[IllegalStateException]
    def register(clientAddress: SocketAddress, handler: ClientHandler): Unit = {
        log info s"Client $clientAddress connected"

        val context = new ClientContext(handler)
        do {
            val oldState = state.get()
            if (oldState.closed) {
                throw new IllegalStateException("Manager closed")
            }
            val newState = oldState.add(clientAddress, context)
            if (state.compareAndSet(oldState, newState)) {
                val oldContext = oldState.get(clientAddress)
                if (oldContext ne null) {
                    // If there is a previous context for the same address,
                    // close that context.
                    oldContext.close(shutdown = true)
                }
                return
            }
        } while (true)
    }

    /**
      * Unregisters an existing client by removing its context and closing all
      * its subscriptions.
      */
    @throws[IllegalStateException]
    def unregister(clientAddress: SocketAddress): Unit = {
        log info s"Client $clientAddress disconnected"

        do {
            val oldState = state.get()
            if (oldState.closed) {
                throw new IllegalStateException("Manager closed")
            }
            val newState = oldState.remove(clientAddress)
            if (state.compareAndSet(oldState, newState)) {
                val context = oldState.get(clientAddress)
                if (context ne null) {
                    context.close(shutdown = false)
                } else {
                    log warn s"Client $clientAddress not found"
                }
                return
            }
        } while (true)
    }

    /**
      * Subscribes a client to a state table. The client must be registered
      * for this operation to succeed.
      */
    @throws[StateTableException]
    @throws[ClientUnregisteredException]
    def subscribe(clientAddress: SocketAddress, requestId: Long,
                  request: Subscribe): Unit = {
        if (state.get.closed) {
            throw new IllegalStateException("Manager closed")
        }

        // Validate the request.
        if (!request.hasObjectClass)
            throw new StateTableException(
                Code.INVALID_ARGUMENT, "SUBSCRIBE request missing object class")
        if (!request.hasObjectId)
            throw new StateTableException(
                Code.INVALID_ARGUMENT, "SUBSCRIBE request missing object identifier")
        if (!request.hasKeyClass)
            throw new StateTableException(
                Code.INVALID_ARGUMENT, "SUBSCRIBE request missing table key class")
        if (!request.hasValueClass)
            throw new StateTableException(
                Code.INVALID_ARGUMENT, "SUBSCRIBE request missing table value class")
        if (!request.hasTableName)
            throw new StateTableException(
                Code.INVALID_ARGUMENT, "SUBSCRIBE request missing table name")

        // Retrieve the client context.
        val context = state.get.get(clientAddress)
        if (context eq null) {
            throw new ClientUnregisteredException(clientAddress)
        }

        // Compute the table key.
        val tableKey = try {
            StateTableKey(Class.forName(request.getObjectClass),
                          request.getObjectId,
                          Class.forName(request.getKeyClass),
                          Class.forName(request.getValueClass),
                          request.getTableName,
                          request.getTableArgumentsList.asScala)
        } catch {
            case e @ (_: LinkageError | _: ExceptionInInitializerError |
                      _: ClassNotFoundException) =>
                throw new StateTableException(
                    Code.INVALID_ARGUMENT,
                    s"SUBSCRIBE request invalid argument: ${e.getMessage}")
        }
        val lastVersion =
            if (request.hasLastVersion) Some(request.getLastVersion)
            else None
        log debug s"Client $clientAddress subscribing to table $tableKey for " +
                  s"version $lastVersion (request ID: $requestId)"

        var subscriptionId = -1L
        do {
            subscriptionId = try {
                context.subscribeTo(tableKey, getOrCreateTableCache(tableKey),
                                    requestId, lastVersion)
            } catch {
                case e: StateTableCacheClosedException => -1L
            }
        } while (subscriptionId < 0)
    }

    /**
      * Unsubscribes a client from a state table.
      */
    @throws[StateTableException]
    @throws[ClientUnregisteredException]
    def unsubscribe(clientAddress: SocketAddress, requestId: Long,
                    request: Unsubscribe): Unit = {
        if (state.get.closed) {
            throw new IllegalStateException("Manager closed")
        }

        // Validate the request.
        if (!request.hasSubscriptionId)
            throw new StateTableException(
                Code.INVALID_ARGUMENT,
                "UNSUBSCRIBE request missing subscription identifier")

        // Retrieve the client context.
        val context = state.get.get(clientAddress)
        if (context eq null) {
            throw new ClientUnregisteredException(clientAddress)
        }

        log debug s"Client $clientAddress unsubscribing from " +
                  s"${request.getSubscriptionId} (request ID: $requestId)"
        context.unsubscribeFrom(request.getSubscriptionId, requestId)
    }

    /**
      * Gets or creates a [[StateTableCache]] for the specified [[StateTableKey]].
      * If the table cache already exists and is not closed, the method reuses
      * the same. Otherwise, it creates a new cache and adds it to the caches
      * map.
      */
    private def getOrCreateTableCache(key: StateTableKey): StateTableCache = {
        var cache = caches.get(key)
        if ((cache eq null) || cache.isClosed) {
            cache = new StateTableCache(
                config, backend.stateTableStore, backend.curator,
                subscriptionCounter, key.objectClass, key.objectId,
                key.keyClass, key.valueClass, key.tableName, key.tableArgs,
                executors((executorIndex.getAndIncrement() % threadCount).toInt),
                caches.remove(key, _))
            cache = caches.putIfAbsent(key, cache) match {
                case null => cache
                case c =>
                    cache.close()
                    c
            }
        }
        cache
    }

}
