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

package org.midonet.packets

import java.net.InetAddress
import java.util.Random

/**
 * Common abstraction for IPv4 and IPv6 addresses
 */
trait IPAddr {
    type T <: IPAddr
    def toString: String
    def toUrlString: String
    def toBytes: Array[Byte]

    /**
     * Provides an IPSubnet from this address and with the given length.
     *
     * @param len defaults to 128
     * @return
     */
    def subnet(len: Int): IPSubnet[T]

    /**
     * Get the next ip to the current one
     *
     * ®return
     */
    def next: T

    def randomTo(limit: T, rand: Random): T

    /** Returns an inclusive range of IPAddr as a Java Iterable, up to
     *  the given argument, or starting from it (order does not matter). */
    def range(that: T): java.lang.Iterable[T]

    def isMcast: Boolean
}

object IPAddr {

    def fromString(s: String): IPAddr =
        if (s.contains(":")) IPv6Addr.fromString(s) else IPv4Addr.fromString(s)

    def fromBytes(addr: Array[Byte]) =
        if (addr.length == 4) IPv4Addr.fromBytes(addr)
        else IPv6Addr.fromBytes(addr)

    def canonicalize(s: String): String = fromString(s).toString

    /** Helper function to create ranges of IPAddr as java Iterable. The range
      * is inclusive of both arguments. */
    def range[T <: IPAddr](from: T, to: T) = {
        new java.lang.Iterable[T]() {
            override def iterator() =
                new java.util.Iterator[T]() {
                    var currentAddr = from
                    var isDone = false

                    override def hasNext = !isDone

                    override def next() = {
                        val ip = currentAddr
                        if (isDone)
                            throw new java.util.NoSuchElementException
                        if (currentAddr == to)
                            isDone = true
                        else
                            currentAddr = currentAddr.next.asInstanceOf[T]
                        ip
                    }

                    override def remove() {
                        throw new UnsupportedOperationException
                    }
                }
        }
    }
}

