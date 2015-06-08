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

import java.util.ArrayList

object IPv4InvalidationArray {
    val NO_ROUTE_VALUE = -1
    val VALUE_MASK = (1 << 6) -1
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
 * offers the list of affected IP addresses by a routing table change.
 *
 * Empty values are represented as IPv4InvalidationArray.NO_VALUE both internally
 * and to the outside. The underlying value for that constant is very implementation
 * dependent, because entries in the data structure encode both the reference
 * and the prefix length in a single int.
 *
 * Implementation notes:
 *
 *   * Implemented as a naive 256-arity trie, with no compaction optimizations.
 *   * Bits below /28 are ignored, making invalidation a bit coarser optimal
 *     as a speed/memory vs precision trade-off. For this reason the 4th level
 *     nodes are 32-element arrays. A fully expanded trie would take 1GB of
 *     memory, as opposed to 16GB if the full 32 bits were tracked.
 *   * Levels 3 and 4 of of the trie use object pools to prevent allocations.
 *   * Levels 1 and 2 are allocated up front and never freed, this is 257 pointer
 *     arrays of 256 positions each.
 */
final class IPv4InvalidationArray {
    import IPv4InvalidationArray._

    private type Leaf = Array[Int]
    private type B3 = Array[Leaf]
    private type B2 = Array[B3]
    private type B1 = Array[B2]

    private def makeB2: B2 = Array.fill[B3](256)(null)
    private def makeB3: B3 = Array.fill[Leaf](256)(null)
    private def makeLeaf: Leaf = Array.fill[Int](16)(NO_VALUE)

    private val leafPool = new ArrayObjectPool[Leaf](2048, _ => makeLeaf)
    private val b3Pool = new ArrayObjectPool[B3](512, _ => makeB3)

    private val root: B1 = Array.fill[B2](256)(makeB2)

    private def b1(v: Int): Int = (v >>> 24) & 0xff
    private def b2(v: Int): Int = (v >>> 16) & 0xff
    private def b3(v: Int): Int = (v >>> 8) & 0xff
    private def b4(v: Int): Int = (v & 0xff) >>> 4

    private def getB2(key: Int): B2 = root(b1(key))
    private def getB3(key: Int): B3 = getB2(key)(b2(key))
    private def getB4(key: Int): Leaf = getB3(key)(b3(key))
    private def get(key: Int): Int = getB4(key)(b4(key))

    /*
     * Deletes and returns all IP addresses under the given prefix as long as they
     * matched a routing table entry (their prefix match length) that is shorter
     * or equal to the prefix length of this request.
     */
    def deletePrefix(key: Int, prefixLen: Int): ArrayList[Int] = {
        val mask = (((1.toLong<<prefixLen) - 1) << (32-prefixLen)).toInt
        val first = key & mask
        val last = first | ((1<<(32-prefixLen))-1)

        val deletions = new ArrayList[Int]()

        def deleteLeaf(leaf: Leaf, prefix: Int): Unit = {
            var i = b4(first)
            while (i <= b4(last)) {
                val originalMatchLen = extractValue(leaf(i))
                if ((originalMatchLen != NO_VALUE) && (originalMatchLen <= prefixLen)) {
                    deletions.add(prefix | (i<<4))
                    leaf(i) = NO_VALUE
                }
                i += 1
            }
        }

        def deleteB3(node: B3, prefix: Int): Unit = {
            var i = b3(first)
            while (i <= b3(last)) {
                if (node(i) ne null) {
                    deleteLeaf(node(i), prefix | (i<<8))
                    if (leafIsEmpty(node(i))) {
                        leafPool.offer(node(i))
                        node(i) = null.asInstanceOf[Leaf]
                    }
                }
                i += 1
            }
        }

        var i = b1(first)
        while (i <= b1(last)) {
            var j = b2(first)
            while (j <= b2(last)) {
                if (root(i)(j) ne null) {
                    deleteB3(root(i)(j), (i<<24) | (j<<16))
                    if (b3IsEmpty(root(i)(j))) {
                        b3Pool.offer(root(i)(j))
                        root(i)(j) = null.asInstanceOf[B3]
                    }
                }
                j += 1
            }
            i += 1
        }

        deletions
    }

    private def getOrMakeLeaf(key: Int): Leaf = {
        if (getB3(key) eq null) {
            val node = b3Pool.take
            getB2(key)(b2(key)) = if (node eq null) makeB3 else node
        }
        if (getB4(key) eq null) {
            val node = leafPool.take
            getB3(key)(b3(key)) = if (node eq null) makeLeaf else node
        }
        getB4(key)
    }

    private def leafIsEmpty(leaf: Leaf): Boolean = {
        var i = 0
        while (i < 16) {
            if (leaf(i) != NO_VALUE)
                return false
            i += 1
        }
        true
    }

    private def b3IsEmpty(node: B3): Boolean = {
        var i = 0
        while (i < 256) {
            if (node(i) ne null)
                return false
            i += 1
        }
        true
    }

    private def cleanIfEmpty(key: Int): Unit = {
        if (leafIsEmpty(getB4(key))) {
            val node = getB3(key)
            leafPool.offer(getB4(key))
            node(b3(key)) = null
            if (b3IsEmpty(node)) {
                b3Pool.offer(node)
                getB2(key)(b2(key)) = null
            }
        }
    }

    /*
     * Inserts or increments the reference count for an IP address and its given
     * prefix match length.
     */
    def ref(key: Int, v: Int): Int = {
        /* Ignore route matches with prefix length of 32, because a route addition
         * will never overshadow such a match, at most it will become a same-prefix
         * route and flows using the pre-existing /32 route are still valid and
         * thus don't need to be tracked. They would only become invalid if their
         * route is removed, something this data structure does not track.
         *
         * The prefix length value we store is incremented by one to make the range
         * of values 0-32, given that -1 is used to mark a no-route match. So,
         * no routes become 0, and /31 matches become /32.
         *
         * Inside deletePrefix we compare the match length with the new route
         * length using <=. Example: a flow doesn't match any route, and gets
         * stored here with a prefix length of 0 (received as -1 by this method).
         * A default, /0, route is added. Because the original corrected match
         * length (-1 + 1 = 0) is <= new route's length (0), the flow will be
         * invalidated.
         *
         * Example 2. A flow matches a default route, and this match is stored
         * here with a corrected match length of 1 (0+1). A new default route
         * is added. Because the stored corrected match length (1) is not <= the
         * new route's prefix length (0), the flow will not be invalidated. Which
         * is correct because the original route it matched has not been deleted.
         */
        if (v < 32) {
            val leaf = getOrMakeLeaf(key)
            val e = leaf(b4(key))
            val count = extractRefCount(e) + 1
            leaf(b4(key)) = makeEntry(count, v+1)
            count
        } else {
            0
        }
    }

    /*
     * Retrieves the prefix match length associated with a given ip address.
     * Returns NO_VALUE if the address is not contained in this invalidation array.
     */
    def apply(key: Int): Int = {
        try {
            extractValue(get(key)) match {
                case NO_VALUE => NO_VALUE
                case c => c - 1
            }
        } catch {
            case e: NullPointerException => NO_VALUE
        }
    }

    /*
     * Decrements the reference count on a particular IP address.
     */
    def unref(key: Int): Int = {
        try {
            val entry = get(key)
            extractRefCount(entry) match {
                case 1 =>
                    getB4(key)(b4(key)) = NO_VALUE
                    cleanIfEmpty(key)
                    0
                case c if c > 1 =>
                    getB4(key)(b4(key)) = makeEntry(c-1, extractValue(entry))
                    c - 1
                case _ => // must be 0 and v == NO_VALUE
                    -1
            }
        } catch {
            case e: NullPointerException => -1
        }
    }

    def isEmpty: Boolean = {
        /* iterate down one level, because we don't clear entries in the
         * first level due to the low amount of overhead in keeping 256 to-level
         * entries around. */
        var i = 0
        while (i < 256) {
            var j = 0
            while (j < 256) {
                if (root(i)(j) ne null)
                    return false
                j += 1
            }
            i += 1
        }
        true
    }

    def nonEmpty = !isEmpty
}
