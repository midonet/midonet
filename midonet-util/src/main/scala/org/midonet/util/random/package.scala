/*
 * Copyright (c) 2016, Midokura SARL.
 */

package org.midonet.util

import scala.reflect.runtime.universe.TypeTag
import scala.util.Random

/**
  * Miscellaneous utilities related to random values
  */
package object random {
    /** Random string with only alphanumerical characters */
    def rndAlphaNum(len: Int): String = Random.alphanumeric.take(len).mkString

    /** Random value from seq */
    def rndElement[S](seq: Seq[S])(implicit tag: TypeTag[S]): Option[S] =
        if (seq.nonEmpty)
            Some(seq(Random.nextInt(seq.length)))
        else
            None

    /** Random value from iterable */
    def rndElement[S](iter: Iterable[S]): Option[S] =
        if (iter.nonEmpty) {
            val rndPosition = Random.nextInt(iter.size)
            iter.slice(rndPosition, rndPosition + 1).headOption
        } else {
            None
        }

    /** Random Long from 0 (inclusive) to max (exclusive) */
    def rndLong(max: Long = Long.MaxValue): Long =
        if (max > 0) (Random.nextLong() & Long.MaxValue) % max
        else throw new IllegalArgumentException("bound must be positive")
}
