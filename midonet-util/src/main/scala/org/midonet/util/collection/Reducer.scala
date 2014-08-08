/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.util.collection

abstract class Reducer[-K, -V, U] extends ((U, K, V) => U) {
    override def apply(v1: U, v2: K, v3: V): U
}
