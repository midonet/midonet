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
import org.scalatest.{OneInstancePerTest, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ObjectPoolTest extends FeatureSpec with Matchers with OneInstancePerTest {
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

    feature ("DynamicPooledObjects are pooled") {
        class PObject(val pool: ObjectPool[PObject]) extends PooledObject {
            override def clear(): Unit = { clearCount += 1 }

            var clearCount = 0
        }

        def initPool(): DynamicObjectPool[PObject] = {
            new DynamicObjectPool[PObject](capacity,
                                           new PObject(_),
                                           (p: PObject) => p.clear())
        }
        scenario ("PooledObjects are reference counted") {
            val pool = initPool()
            val pobject = pool.take

            pool.available should be (0)
            pool.size should be (1)
            pobject.ref()
            pobject.ref()

            pobject.unref()
            pool.available should be (0)
            pool.size should be (1)

            pobject.unref()
            pool.available should be (1)
            pool.size should be (1)
        }

        scenario ("DynamicPool releases the objects accordingly") {
            val pool = initPool()
            val pobject1 = pool.take
            val pobject2 = pool.take

            pool.available should be (0)
            pool.size should be (2)

            pool.offer(pobject2)

            pool.available should be (1)

            pool.release

            pobject1.clearCount should be (1)
            pobject2.clearCount should be (1)
            pool.size should be (0)
            pool.available should be (0)
        }

        scenario ("The pool is bounded") {
            val pool = initPool()
            (0 until capacity) foreach { _ => pool.take should not be null }
            pool.take should be (null)
            pool.available should be (0)

            (0 until capacity * 2) foreach { i => pool offer new PObject(pool) }
            pool.available should be (capacity)
            (0 until capacity) foreach { _ => pool.take should not be null }
            pool.take should be (null)
            pool.available should be (0)
        }
    }
}
