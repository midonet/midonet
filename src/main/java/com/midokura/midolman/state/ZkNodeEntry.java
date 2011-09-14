package com.midokura.midolman.state;

public class ZkNodeEntry<X, Y> {
    public X key;
    public Y value;

    public ZkNodeEntry(X key, Y value) {
        this.key = key;
        this.value = value;
    }    
}
