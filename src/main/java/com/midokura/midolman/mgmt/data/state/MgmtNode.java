package com.midokura.midolman.mgmt.data.state;

import com.midokura.midolman.state.ZkNodeEntry;

public class MgmtNode<S, T, U> extends ZkNodeEntry<S, T> {

    public U extra = null;

    public MgmtNode(S key, T value, U extra) {
        super(key, value);
        this.extra = extra;
    }

}
