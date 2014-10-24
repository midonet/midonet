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
package org.midonet.cluster.neutron

/**
 * Defines a set of operations to be performed by the Neutron data importer on a
 * single high-level Neutron model object or the entire set of Neutron model
 * data.
 */
object OpType extends Enumeration {
    type OpType = Value

    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)
    val Flush = Value(4)

    private val ops = Array(Create, Delete, Update, Flush)
    def valueOf(i: Int) = ops(i - 1)
}