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

package org.midonet.midolman

import com.google.common.collect.Lists

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.CallbackRegistry.{CallbackSpec, SerializableCallback}

@RunWith(classOf[JUnitRunner])
class CallbackRegistryTest extends FeatureSpec with Matchers {
    feature("callback registry") {
        scenario("called in correct order") {
            val reg = new CallbackRegistryImpl
            val queue = Lists.newLinkedList[String]()
            val cb1 = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb1-" + new String(args))
                    }
                })
            val cb2 = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb2-" + new String(args))
                    }
                })
            val callbacks = Lists.newArrayList(
                new CallbackSpec(cb2, "foobar".getBytes),
                new CallbackSpec(cb1, "bazfoo".getBytes))
            reg.runAndClear(callbacks)
            queue should contain inOrderOnly ("cb2-foobar", "cb1-bazfoo")
        }

        scenario("multiple calls to same callback") {
            val reg = new CallbackRegistryImpl
            val queue = Lists.newLinkedList[String]()
            val cb = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb-" + new String(args))
                    }
                })
            val callbacks = Lists.newArrayList(
                new CallbackSpec(cb, "foobar".getBytes),
                new CallbackSpec(cb, "bazfoo".getBytes))
            reg.runAndClear(callbacks)
            queue should contain inOrderOnly ("cb-foobar", "cb-bazfoo")
        }

        scenario("callbacks called once") {
            val reg = new CallbackRegistryImpl
            val queue = Lists.newLinkedList[String]()
            val cb = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb-" + new String(args))
                    }
                })
            val callbacks = Lists.newArrayList(
                new CallbackSpec(cb, "foobar".getBytes))
            1.to(10) foreach { i => reg.runAndClear(callbacks) }
            queue should contain only ("cb-foobar")
            callbacks shouldBe empty
        }

        scenario("callback can be removed") {
            val reg = new CallbackRegistryImpl
            val queue = Lists.newLinkedList[String]()
            val cb1 = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb1-" + new String(args))
                    }
                })
            val cb2 = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb2-" + new String(args))
                    }
                })
            val cb3 = reg.registerCallback(
                new SerializableCallback() {
                    override def call(args: Array[Byte]): Unit = {
                        queue.add("cb3-" + new String(args))
                    }
                })

            val callbacks = Lists.newArrayList(
                new CallbackSpec(cb1, "foobar".getBytes),
                new CallbackSpec(cb2, "bazfoo".getBytes),
                new CallbackSpec(cb3, "barbaz".getBytes))

            reg.unregisterCallback(cb2)
            reg.runAndClear(callbacks)

            queue should contain inOrderOnly ("cb1-foobar", "cb3-barbaz")
        }
    }
}
