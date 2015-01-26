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

import rx.functions.{Func2, Action0, Func1}

package object functors {
    def makeRunnable(fn: => Unit) = new Runnable {
        override def run(): Unit = fn
    }
    def makeFunc1[T1, R](fn: T1 => R) = new Func1[T1, R] {
        override def call(t1: T1): R = fn(t1)
    }
    def makeFunc2[T1, T2, R](fn: (T1, T2) => R) = new Func2[T1, T2, R] {
        override def call(t1: T1, t2: T2): R = fn(t1, t2)
    }
    def makeAction0(fn: => Unit) = new Action0 {
        override def call(): Unit = fn
    }
}
