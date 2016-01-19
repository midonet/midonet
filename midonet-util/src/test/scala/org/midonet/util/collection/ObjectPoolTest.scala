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

package org.midonet.util.collection

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class ObjectPoolTest extends FeatureSpec with Matchers {
    val capacity = 32

    feature ("ObjectPool pools objects") {
        var created = 0
        val pool = new ArrayObjectPool[Object](capacity, _ => {
            created += 1
            new Object()
        })

        scenario ("objects are pre-allocated") {
            created should be (capacity)
        }

        scenario ("the pool is bounded") {
            (0 until capacity) foreach { _ => pool.take should not be null }
            pool.take should be (null)
            pool.available should be (0)

            (0 until capacity * 2) foreach { _ => pool offer new Object }
            pool.available should be (capacity)
            (0 until capacity) foreach { _ => pool.take should not be null }
            pool.take should be (null)
            pool.available should be (0)
        }
    }

    feature ("PooledObjects are pooled") {
        class PObject(val pool: ObjectPool[PObject]) extends PooledObject {
            override def clear(): Unit = { }
        }

        val pool = new ArrayObjectPool[PObject](capacity, new PObject(_))

        scenario ("PooledObjects are reference counted") {
            val pobject = pool.take
            pool.available should be (capacity - 1)

            pobject.ref()
            pobject.ref()

            pobject.unref()
            pool.available should be (capacity - 1)

            pobject.unref()

            pool.available should be (capacity)
        }

        scenario ("Can't have negative reference count") {
            intercept[IllegalArgumentException] {
                pool.take.unref()
            }
        }
    }

    feature ("IndexableObjectPool pools objects") {
        var created = 0
        val pool = new IndexableObjectPool[IndexableObjectPool.Indexable](capacity, _ => {
            created += 1
            new Object with IndexableObjectPool.Indexable
        })

        scenario ("objects are pre-allocated") {
            created should be (capacity)
        }

        scenario ("objects have an index") {
            val indexes = 0 until capacity * 4
            indexes map { _ => pool.take.index } sortBy identity should be (indexes)
        }

        scenario ("the pool is unbounded") {
            (0 until capacity * 4) foreach { _ => pool.take should not be null }
        }

        scenario ("objects are reused") {
            val set = mutable.Set[IndexableObjectPool.Indexable]()
            (0 until capacity * 4) foreach { _ => set += pool.take }
            set foreach pool.offer
            (0 until capacity * 4) foreach { _ =>
                set should contain (pool.take)
            }
        }
    }
}
