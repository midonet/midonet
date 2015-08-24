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

package org.midonet.southbound.vtep.mock

import java.util.concurrent.{Executor, TimeUnit}

import com.google.common.util.concurrent.ListenableFuture

/**
 * A mocked listenable future for use with mocked ovsdb components
 */
class MockListenableFuture[V](v: V) extends ListenableFuture[V] {
    override def addListener(listener: Runnable, executor: Executor): Unit =
        executor.execute(listener)
    override def isCancelled: Boolean = false
    override def get(): V = v
    override def get(l: Long, timeUnit: TimeUnit): V = v
    override def cancel(b: Boolean): Boolean = false
    override def isDone: Boolean = true
}

class MockFailedListenableFuture[V](t: Throwable) extends ListenableFuture[V] {
    override def addListener(listener: Runnable, executor: Executor): Unit =
        executor.execute(listener)
    override def isCancelled: Boolean = false
    override def get(): V = throw t
    override def get(l: Long, timeUnit: TimeUnit): V = throw t
    override def cancel(b: Boolean): Boolean = false
    override def isDone: Boolean = true
}
