/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util

import rx.functions.Func1

package object functors {
    def makeFunc1[T1, R](fn: T1 => R) = new Func1[T1, R] {
        override def call(t1: T1): R = fn(t1)
    }
}
