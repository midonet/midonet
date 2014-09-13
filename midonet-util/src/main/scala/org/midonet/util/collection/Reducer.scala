/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util.collection

abstract class Reducer[-K, -V, U] extends ((U, K, V) => U) {
    override def apply(acc: U, key: K, value: V): U
}
