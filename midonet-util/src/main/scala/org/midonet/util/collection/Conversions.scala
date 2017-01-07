/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.util.collection

object Conversions {

    implicit def asScala[T](iterable: java.lang.Iterable[T]): AsScalaForeach[T] = {
        new AsScalaForeach[T](iterable)
    }

    /**
      * Provides Scala foreach capabilities to Java iterables via a wrapping
      * with an [[AnyVal]] class that prevents additional allocations at runtime
      * as opposed to the conversions provided by scala.collections.
      */
    class AsScalaForeach[T](val underlying: java.lang.Iterable[T])
        extends AnyVal {

        def foreach[U](f: T => U): Unit = {
            val iterator = underlying.iterator()
            while (iterator.hasNext) {
                f apply iterator.next()
            }
        }
    }

}
