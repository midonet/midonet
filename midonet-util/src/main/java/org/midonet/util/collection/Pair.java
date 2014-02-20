/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util.collection;

/**
 * Interface of a pair which has two values, a left value and a right value.
 *
 * @param <L> Type of the left value
 * @param <R> Type of the right value
 */
public interface Pair<L, R> {
    /**
     * Get the left value.
     *
     * @return The left value which type is L.
     */
    public L getLeft();

    /**
     * Replace the left value with the given argument which type of L.
     *
     * @param left
     */
    public void setLeft(L left);

    /**
     * Get the right value.
     *
     * @return The right value which type is R.
     */
    public R getRight();

    /**
     * Replace the right value with the given argument which type of R.
     *
     * @param right
     */
    public void setRight(R right);
}
