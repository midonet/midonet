/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.util.collection;

import com.google.common.base.Objects;

/**
 * Implementation of a pair which has the two immutable values, a left value
 * and a right value.
 *
 * @param <L> Type of the left value
 * @param <R> Type of the right value
 */
public class MutablePair<L, R> implements Pair<L, R> {
    private L left;
    private R right;

    public MutablePair() {
    }

    public MutablePair(L left, R right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public L getLeft() {
        return left;
    }

    @Override
    public void setLeft(L left) {
        this.left = left;
    }

    @Override
    public R getRight() {
        return right;
    }

    @Override
    public void setRight(R right) {
        this.right = right;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(left, right);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null | !(getClass().equals(o.getClass())))
            return false;

        MutablePair that = (MutablePair) o;
        return Objects.equal(left, that.left) &&
                Objects.equal(right, that.right);
    }
}
