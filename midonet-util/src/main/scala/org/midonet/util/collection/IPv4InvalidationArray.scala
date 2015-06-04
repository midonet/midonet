/*
 * Copyright 2015 Midokura SARL
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

import java.util.{List => JList, ArrayList}

object IPv4InvalidationArray {
    val VALUE_MASK = 0x3f
    val NO_VALUE = VALUE_MASK

    def makeEntry(refCount: Int, v: Int): Int = (refCount << 6) | (v & VALUE_MASK)
    def extractRefCount(entry: Int) = entry >> 6
    def extractValue(entry: Int) = entry & VALUE_MASK
}

/*
 * A data structure to track IP addresses whose flows should be invalidated
 * upon a routing table change.
 *
 * It stores IP addresses along with their reference counts and the prefix length
 * of the route that match them.
 *
 * Stored IPs can be ref'ed/unref'ed as flows come an go. The data structure
 * offers the list of affected IP addresses.
 *
 * Empty values are represented as IPv4InvalidationArray.NO_VALUE both internally
 * and to the outside. The underlying value for that constant is very implementation
 * dependent, because entries in the datastructure encode both the reference
 * and the prefix length in a single int.
 *
 * Implementation notes:
 *
 *   * Implemented as a very naive judy-array, with no compaction optimizations.
 *   * Levels 3 and 4 of of the trie use object pools to prevent allocations.
 */
class IPv4InvalidationArray {
    import IPv4InvalidationArray._

    trait PrefixInvalidator {
        def deletePrefix(key: Int, prefixLen: Int, deletions: ArrayList[Int]): Unit
    }

    abstract class Node[ChildNode] {
        var size = 0
        def EMPTY: ChildNode
        def SHIFT: Int

        def array: Array[ChildNode]

        def apply(i: Int): ChildNode = array(index(i))

        def update(i: Int, node: ChildNode): Unit = {
            array(index(i)) = node
            size += 1
        }

        def clear(i: Int): ChildNode = {
            val v = apply(i)
            array(index(i)) = EMPTY
            size -= 1
            v
        }

        def isEmpty = size == 0
        def nonEmpty = !isEmpty

        def localPrefixLen(prefixLen: Int) = Math.max(0, Math.min(8, prefixLen - 24 + SHIFT))

        def index(v: Int): Int = (v >> SHIFT) & 0xff
    }

    abstract class NonTerminal[ChildNode <: PrefixInvalidator] extends Node[ChildNode] with PrefixInvalidator {
        override def deletePrefix(key: Int, prefixLen: Int, deletions: ArrayList[Int]): Unit = {
            val lpl = localPrefixLen(prefixLen)
            if (lpl < 8) {
                val suffixLen = 8 - lpl
                val firstMask = 0xff & (((1<<lpl) - 1) << suffixLen)
                val first = index(key) & firstMask
                val last = first | ((1<<suffixLen) - 1)

                var i = first
                while (i <= last) {
                    if (array(i) != EMPTY)
                        array(i).deletePrefix(key, prefixLen, deletions)
                    i += 1
                }
            } else {
                array(index(key)).deletePrefix(key, prefixLen, deletions)
            }
        }
    }

    final class B0Node extends NonTerminal[B1Node] with PrefixInvalidator {
        override val array = new Array[B1Node](256)
        override val EMPTY = null
        override val SHIFT = 24
    }
    final class B1Node extends NonTerminal[B2Node] with PrefixInvalidator {
        override val array = new Array[B2Node](256)
        override val EMPTY = null
        override val SHIFT = 16
    }
    final class B2Node extends NonTerminal[Leaf] with PrefixInvalidator {
        override val array = new Array[Leaf](256)
        override val EMPTY = null
        override val SHIFT = 8
    }
    final class Leaf extends Node[Int] with PrefixInvalidator {
        override val EMPTY = NO_VALUE
        override val SHIFT = 0
        override val array = Array.fill[Int](256)(EMPTY)

        override def deletePrefix(key: Int, prefixLen: Int, deletions: ArrayList[Int]): Unit = {
            val lpl = localPrefixLen(prefixLen)
            val suffixLen = 8 - lpl
            val firstMask = 0xff & (((1<<lpl) - 1) << suffixLen)
            val first = index(key) & firstMask
            val last = first | ((1<<suffixLen) - 1)

            var i = first
            while (i <= last) {
                val originalMatchLen = extractValue(array(i))
                if ((originalMatchLen != EMPTY) && (originalMatchLen <= prefixLen)) {
                    deletions.add(key | i)
                    array(i) = EMPTY
                    size -= 1
                }
                i += 1
            }
        }
    }

    private val root = new B0Node()

    private val leafPool = new ArrayObjectPool[Leaf](2048, _ => new Leaf())
    private val b2Pool = new ArrayObjectPool[B2Node](512, _ => new B2Node())

    private def getOrMakeLeaf(key: Int): Leaf = {
        if (root(key) == null)
            root(key) = new B1Node()
        if (root(key)(key) == null) {
            val node = b2Pool.take
            root(key)(key) = if (node eq null) new B2Node() else node
        }
        if (root(key)(key)(key) == null) {
            val leaf = leafPool.take
            root(key)(key)(key) = if (leaf eq null) new Leaf() else leaf
        }
        root(key)(key)(key)
    }

    private def cleanIfEmpty(key: Int): Unit = {
        if (root(key)(key)(key).isEmpty) {
            leafPool.offer(root(key)(key).clear(key))
            if (root(key)(key).isEmpty)
                b2Pool.offer(root(key).clear(key))
        }
    }

    /*
     * Inserts or increments the reference count for an IP address and its given
     * prefix match length.
     */
    def ref(key: Int, v: Int): Unit = {
        val leaf = getOrMakeLeaf(key)
        val e = leaf(key)
        leaf(key) = makeEntry(extractRefCount(e) + 1, v)
    }

    /*
     * Deletes and returns all IP addresses under the given prefix as long as they
     * matched a routing table entry (their prefix match length) that is shorter
     * or equal to the prefix length of this request.
     */
    def deletePrefix(key: Int, prefixLen: Int): JList[Int] = {
        val values: ArrayList[Int] = new ArrayList()
        root.deletePrefix(key, prefixLen, values)
        values
    }

    /*
     * Retrieves the prefix match length associated with a given ip address.
     * Returns NO_VALUE if the address is not contained in this invalidation array.
     */
    def apply(key: Int): Int = {
        try extractValue(root(key)(key)(key)(key)) catch {
            case e: NullPointerException => NO_VALUE
        }
    }

    /*
     * Decrements the reference count on a particular IP address.
     */
    def unref(key: Int): Int = {
        try {
            val entry = root(key)(key)(key)(key)
            val v = extractValue(entry)
            extractRefCount(entry) match {
                case 1 =>
                    root(key)(key)(key).clear(key)
                    cleanIfEmpty(key)
                case count if count > 1 => root(key)(key)(key)(key) = makeEntry(count -1, v)
                case _ => // must be 0 and v == NO_VALUE
            }
            v
        } catch {
            case e: NullPointerException => -1
        }
    }
}
