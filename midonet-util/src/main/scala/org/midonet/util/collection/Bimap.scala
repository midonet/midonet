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

package org.midonet.util.collection;

/** Immutable bijective map */
object Bimap {

    def apply[A,B](mappings: TraversableOnce[(A,B)]) = new Bimap[A,B]() ++ mappings
}

class Bimap[A,B](private val forward: Map[A,B] = Map[A,B](),
                 val inverse: Map[B,A] = Map[B,A]()) extends Iterable[(A,B)] {

    def empty = new Bimap[A,B]()

    override def iterator = forward.iterator

    def get(key: A): Option[B] = forward.get(key)

    def getOrElse(key: A, default: => B) = get(key).getOrElse(default)

    def contains(k: A) = forward.contains(k)

    def keys: Iterable[A] = forward.keys

    def values: Iterable[B] = inverse.keys

    def + (kv: (A, B)): Bimap[A, B] =
        new Bimap[A,B](forward + kv, inverse + kv.swap)

    def ++ (other: TraversableOnce[(A, B)]): Bimap[A, B] =
        other.foldLeft(this)(_ + _)

    def - (k: A): Bimap[A, B] = get(k) match {
        case Some(v) => new Bimap(forward - k, inverse - v)
        case None => this
    }

    def -- (other: TraversableOnce[(A, B)]): Bimap[A, B] =
        other.foldLeft(this)(_ - _._1)

}
