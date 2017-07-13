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

    feature ("On demand pooled objects are pooled on demand") {
        class PObject(val pool: ObjectPool[PObject]) extends PooledObject {
            override def clear(): Unit = { }
        }

        val pool = new OnDemandArrayObjectPool[PObject](capacity, new PObject(_))

        scenario ("Objects are not allocated until taken") {
            pool.pool(pool.capacity - 1) shouldBe null
            val p1 = pool.take
            pool.pool(pool.capacity - 1) should not be null
        }

        scenario ("Objects are not removed from the array once offered back") {
            val p1 = pool.take
            pool.pool(pool.capacity - 1) should not be null
            val p2 = pool.take
            pool.pool(pool.capacity - 2) should not be null
            pool.offer(p2)
            pool.pool(pool.capacity - 2) should not be null
        }

        scenario ("Releasing objects only removes those not used") {
            // Taking the whole pool
            val taken = for (i <- 0 until capacity) yield pool.take

            // Make sure every object is allocated
            for (i <- 0 until capacity) pool.pool(i) should not be null

            // Release has no effect if pool members are in use
            pool.release()
            pool.available shouldBe 0
            for (i <- 0 until capacity) pool.pool(i) should not be null

            // Return half of objects to the pool
            for (i <- 0 until capacity/2) pool.offer(taken(i))

            // Objects are still allocated
            pool.available shouldBe capacity/2
            for (i <- 0 until capacity) pool.pool(i) should not be null

            // Release the available pooled objects
            pool.release()
            pool.available shouldBe capacity/2
            for (i <- 0 until capacity/2) pool.pool(i) shouldBe null
            for (i <- capacity/2 until capacity) pool.pool(i) should not be null

            // Oferring the other half and releasing makes all pool elems null
            for (i <- capacity/2 until capacity) pool.offer(taken(i))
            pool.release()
            for (i <- 0 until capacity) pool.pool(i) shouldBe null

        }
    }

    feature ("Reusable object pools are reused") {

        var elem = 0
        def factory(): Int = {
            val current = elem
            elem += 1
            current
        }

        scenario ("Empty list") {
            val size = 0
            elem = 0

            val pool = new ReusablePool[Int](size, factory)
            intercept[Exception] {
                pool.next
            }
        }

        scenario ("Non empty list - getting objects wrap around") {
            val size = 10
            elem = 0

            val pool = new ReusablePool[Int](size, factory)

            (0 until 25) foreach { x =>
                pool.next shouldBe x % size
            }
        }

    }
}
