/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.util

import scala.collection.mutable

/**
 * Assorted collection toolbox.
 */
package object collection {

    /**
     * Map links a type with an instance, the first time mapOnce is invoked it
     * will create and store the association. Subsequent calls will receive the
     * first created instance.
     */
    trait MapperToFirstCall {

        val map = mutable.Map[Class[_], AnyRef]()

        def mapOnce[T <: AnyRef](typeObject: Class[T])(call: => T): T = {
            map.get(typeObject) match {
                case Some(instance) =>
                    instance.asInstanceOf[T]
                case None =>
                    val instance = call
                    map.put(typeObject, instance)
                    instance
            }
        }
    }


}
