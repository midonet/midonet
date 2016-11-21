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
package org.midonet.midolman.config

/*
 * All the masks we use for tunnel keys in one place, so that we don't have
 * track them all down each time we add a new one, to ensure they don't
 * clash.
 *
 * Tunnel keys have a 24 bits (limit imposed by vxlan).
 * The first bit is uses for tracing. The next 3 are the tunnel key type.
 * There are 8 possible types. 4 are currently used.
 * The remaining bits are used for the key itself.
 *
 *  0                   1                   2
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T|Type | Key bits                                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
object TunnelKeys {
    private val MaskShift = 20
    private val MaxKey = (0x1 << 24) - 1
    private val TraceBitMask = (0x8 << MaskShift)
    private val TypeBits = (0x7 << MaskShift)
    private val KeyBits = (0x1 << MaskShift) - 1

    final class TunnelKeyType private[config] (bits: Byte) {
        val mask = bits << MaskShift

        def isOfType(key: Int): Boolean = (key & TypeBits) == mask
        def apply(key: Int): Int = (key & KeyBits) | mask
    }

    object TraceBit {
        def set(key: Int): Int = key | TraceBitMask
        def isSet(key: Int): Boolean = (key & TraceBitMask) == TraceBitMask
        def clear(key: Int): Int = key & ~TraceBitMask
    }

    val LegacyPortType = new TunnelKeyType(0x0)
    val LocalPortGeneratedType = new TunnelKeyType(0x1)
    val Fip64Type = new TunnelKeyType(0x2)

    /* Flow state always uses 0xffffff as tunnel key */
    val FlowStateType = new TunnelKeyType(0x7)
}
