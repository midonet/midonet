/*
 * Copyright 2013 Midokura Europe SARL
 */
package org.midonet.util;

/**
 * Very basic implementation of a range that allows checking if a given value
 * is within it.
 */
public class Range<E extends Comparable<E>> {

    private E start;
    private E end;

    /**
     * To allow deserialization.
     */
    public Range() {}

    public Range(E start, E end) {
        if (start.compareTo(end) > 0)
            throw new IllegalArgumentException("Range start > range end!");
        this.start = start;
        this.end = end;
    }

    /**
     * Convenience constructor when the range stars and ends at the same point
     */
    public Range(E startAndEnd) {
        this(startAndEnd, startAndEnd);
    }

    /**
     * Required for serialization.
     * @param start
     */
    public void setStart(E start) {
        this.start = start;
    }

    /**
     * Required for serialization
     * @param end
     */
    public void setEnd(E end) {
        this.end = end;
    }

    public E start() {
        return this.start;
    }

    public E end() {
        return this.end;
    }

    /**
     * Tells whether the given value inside the range?
     */
    public boolean isInside(E value) {
        return (this.start.compareTo(value) <= 0) &&
               (this.end.compareTo(value) >= 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Range)) return false;

        Range range = (Range) o;

        if (end != null ? !end.equals(range.end) : range.end != null)
            return false;
        if (start != null ? !start.equals(range.start) : range.start != null)
            return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = start != null ? start.hashCode() : 0;
        result = 31 * result + (end != null ? end.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Range [" + start + ", " + end + "]";
    }

}
