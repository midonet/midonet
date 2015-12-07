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

package org.midonet.cluster.services.containers.schedulers

import scala.util.Random

import com.typesafe.scalalogging.Logger

import org.scalatest.{Suite, BeforeAndAfter}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.services.MidonetBackend
import org.midonet.util.concurrent.SameThreadButAfterExecutorService

trait SchedulersTest extends Suite with BeforeAndAfter {

    protected var store: InMemoryStorage = _
    protected var context: Context = _
    protected val random = new Random()

    before {
        val executor = new SameThreadButAfterExecutorService
        val log = Logger(LoggerFactory.getLogger("containers"))
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, store)
        context = Context(store, store, executor, Schedulers.from(executor), log)
    }

}
