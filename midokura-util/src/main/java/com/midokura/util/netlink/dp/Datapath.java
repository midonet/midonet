/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.netlink.dp;

/**
 * Datapath abstraction.
 */
public class Datapath {
    public Datapath(int index, String name) {
        this.name = name;
        this.index = index;
    }

    int index;
    String name;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Datapath datapath = (Datapath) o;

        if (index != datapath.index) return false;
        if (!name.equals(datapath.name)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = index;
        result = 31 * result + name.hashCode();
        return result;
    }
}
