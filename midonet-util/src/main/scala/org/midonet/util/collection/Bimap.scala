/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.util.collection;

/** Immutable bijective map */
class Bimap[A,B](private val forward: Map[A,B] = Map[A,B](),
                 val inverse: Map[B,A] = Map[B,A]()) extends Iterable[(A,B)] {

    def empty = new Bimap[A,B]()

    override def iterator = forward.iterator

    def get(key: A): Option[B] = forward.get(key)

    def getOrElse(key: A, default: => B) = get(key).getOrElse(default)

    def contains(k: A) = forward.contains(k)

    def keys: Iterable[A] = forward.keys

    def values: Iterable[B] = inverse.keys

    def + (kv: (A, B)): Bimap[A, B] =
        new Bimap[A,B](forward + kv, inverse + kv.swap)

    def ++ (other: TraversableOnce[(A, B)]): Bimap[A, B] =
        other.foldLeft(this)(_ + _)

    def - (k: A): Bimap[A, B] = get(k) match {
        case Some(v) => new Bimap(forward - k, inverse - v)
        case None => this
    }

    def -- (other: TraversableOnce[(A, B)]): Bimap[A, B] =
        other.foldLeft(this)(_ - _._1)

}
