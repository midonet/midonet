/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.packets

import java.util.{NoSuchElementException, Random}

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

}

object IPAddr {
    def fromString(s: String): IPAddr =
        if (s.contains(":")) IPv6Addr.fromString(s) else IPv4Addr.fromString(s)

    def canonicalize(s: String): String = fromString(s).toString

    /** Helper function to create ranges of IPAddr as java Iterable. The range
     *  is inclusive of both arguments. */
    def range[T <: IPAddr](from: T, to: T) = {
        new java.lang.Iterable[T]() {
            override def iterator() =
                new java.util.Iterator[T]() {
                    var currentAddr = from
                    var isDone = false
                    override def hasNext() = !isDone
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
                        throw new UnsupportedOperationException;
                    }
                }
        }
    }
}
