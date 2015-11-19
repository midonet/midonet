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

import rx.functions._

package object functors {
    def makeRunnable(fn: => Unit) = new Runnable {
        override def run(): Unit = fn
    }
    def makeFunc0[R](fn: => R) = new Func0[R] {
        override def call(): R = fn
    }
    def makeFunc1[T1, R](fn: T1 => R) = new Func1[T1, R] {
        override def call(t1: T1): R = fn(t1)
    }
    def makeFunc2[T1, T2, R](fn: (T1, T2) => R) = new Func2[T1, T2, R] {
        override def call(t1: T1, t2: T2): R = fn(t1, t2)
    }
    def makeFunc3[T1, T2, T3, R](fn: (T1, T2, T3) => R) =
        new Func3[T1, T2, T3, R] {
            override def call(t1: T1, t2: T2, t3: T3): R = fn(t1, t2, t3)
        }
    def makeFunc4[T1, T2, T3, T4, R](fn: (T1, T2, T3, T4) => R) =
        new Func4[T1, T2, T3, T4, R] {
            override def call(t1: T1, t2: T2, t3: T3, t4: T4): R =
                fn(t1, t2, t3, t4)
        }

    def makeAction0(fn: => Unit) = new Action0 {
        override def call(): Unit = fn
    }
    def makeAction1[T1](fn: (T1) => Unit) = new Action1[T1] {
        override def call(t1: T1): Unit = fn(t1)
    }
    def makeCallback0(fn: => Unit) = new Callback0 {
        override def call(): Unit = fn
    }

    def makePredicate(fn: => Boolean) = new Predicate {
        override def check(): Boolean = fn
    }

}
