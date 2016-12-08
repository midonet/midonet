/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.midolman.vpp

import java.util.{BitSet => JBitSet}

object VppVrfs extends Enumeration {

    // Enumeration values are Ints
    type Value = Int

    // Pre-allocate the VRF bit set with for up to 16,384 downlink ports.
    final val MaxNumVrfs = 0x4000

    // Reserved VRFs
    val DefaultVrf = Value(0)
    val FlowStateOutputVrf = Value(1)
    val FlowStateInputVrf = Value(2)

    def getBitSet(): JBitSet = {
        val bset = new JBitSet(MaxNumVrfs)
        VppVrfs.values.foreach(v => bset.set(v.id))
        bset
    }
}
