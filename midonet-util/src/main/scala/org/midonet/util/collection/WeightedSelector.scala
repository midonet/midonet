/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.util.collection

import scala.util.Random

trait HasWeight {
    def weight: Int
}

/**
 * Constructs a WeightedSelector for a traversable collection of objects
 * with weights.
 */
object WeightedSelector {

    private def nextEntry[T <: HasWeight](prev: WeightTableEntry[T], t: T) =
        WeightTableEntry(prev.cumWeight + t.weight, t)

    def apply[T <: HasWeight](ts: Traversable[T]): WeightedSelector[T] = {
        val seed = WeightTableEntry(0, null.asInstanceOf[T])
        val nonzeroView = ts.view.filter(_.weight > 0)
        val weightTable = nonzeroView.scanLeft(seed)(nextEntry).drop(1).toArray
        new WeightedSelector[T](weightTable)
    }
}

/**
 * Entry for weight table. CumWeight is the cumulative weight of this
 * entry and all preceding entries.
 */
private
case class WeightTableEntry[T](val cumWeight: Int, val obj: T)

/**
 * Performs weighted random selection from the specified weight table.
 * Constructor is private; use companion object to create instances.
 */
class WeightedSelector[T] private (weightTable: Array[WeightTableEntry[T]]) {

    val totalWeight = weightTable.last.cumWeight

    /**
     * Testing hook to allow efficient checking of the results for all
     * possible random values in the range [0, totalWeight). For
     * general use, use the parameterless overload.
     */
    protected[collection] def select(rand: Int): T = {
        var start = 0
        var end = weightTable.length - 1
        while (start < end) {
            val mid = (start + end) / 2
            val midWeight = weightTable(mid).cumWeight
            if (rand < midWeight) {
                end = mid
            } else if (rand >= midWeight) {
                start = mid + 1
            }
        }
        // start == end at this point.
        weightTable(start).obj
    }

    /**
     * Performs weighted random selection from its weight table.
     */
    def select(): T = {
        select(Random.nextInt(totalWeight))
    }
}
