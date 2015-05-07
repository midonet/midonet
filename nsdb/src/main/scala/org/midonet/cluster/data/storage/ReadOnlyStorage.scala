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

package org.midonet.cluster.data.storage

import scala.concurrent.Future

import org.midonet.cluster.data.ObjId

/**
 * A trait defining the read-only storage service API.
 */
trait ReadOnlyStorage {
    /**
     * Asynchronous method that gets the specified instance of the specified
     * class from storage. If the value is available in the internal cache,
     * the returned future is completed synchronously.
     */
    @throws[NotFoundException]
    def get[T](clazz: Class[T], id: ObjId): Future[T]

    /**
     * Asynchronous method that gets the specified instances of the specified
     * class from storage. The method returns a sequence a futures,
     * corresponding to the retrieval of each requested instance. The futures
     * will fail for the objects that could not be retrieved from storage. The
     * futures for the objects that are cached internally, will complete
     * synchronously.
     */
    def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId]): Future[Seq[T]]

    /**
     * Asynchronous method that gets all the instances of the specified class
     * from the storage. The method returns a future with a sequence a futures.
     * The outer future completes when the sequence of objects has been
     * retrieved from storage. The inner futures complete when the data of each
     * object has been retrived from storage.
     */
    def getAll[T](clazz: Class[T]): Future[Seq[T]]

    /**
     * Asynchronous method that indicated if the specified object exists in the
     * storage.
     */
    def exists(clazz: Class[_], id: ObjId): Future[Boolean]
}
