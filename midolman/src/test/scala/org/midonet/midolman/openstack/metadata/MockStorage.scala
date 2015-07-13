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

package org.midonet.cluster.data

import scala.concurrent.Future

import org.midonet.cluster.data.storage.ReadOnlyStorage

class MockStorage(val data: Any) extends ReadOnlyStorage {
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        data.asInstanceOf[Future[T]]
    }

    /* The following methods are not used */
    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] =
        Future.successful(false)
    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = 
        Future.successful(List())
    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Future[Seq[T]] =
        Future.successful(List())
}
