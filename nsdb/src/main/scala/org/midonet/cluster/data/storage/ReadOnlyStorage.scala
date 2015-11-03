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
     * class from storage.  This method *always* goes to the backend storage
     * in order to fetch the latest version of the entity.
     */
    def get[T](clazz: Class[T], id: ObjId): Future[T]

    /**
     * Asynchronously gets the specified instances of the specified class from
     * storage. The future completes when all instances have been successfully
     * retrieved, or fails if any of the requested instances cannot be
     * retrieved.  Each element will be fetched from the backend storage in
     * order to retrieve the latest version.
     */
    def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId]): Future[Seq[T]]

    /**
     * Asynchronously gets all instances of the specified class. The future
     * completes when all instances have been successfully retrieved, or fails
     * if any of the requested instances cannot be retrieved. Each element
     * will be fetched from the backend storage in order to retrieve the
     * latest version.
     */
    def getAll[T](clazz: Class[T]): Future[Seq[T]]

    /**
     * Asynchronous method that indicated if the specified object exists in the
     * storage.
     */
    def exists(clazz: Class[_], id: ObjId): Future[Boolean]
}
