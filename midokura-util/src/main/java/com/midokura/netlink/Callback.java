/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.netlink;

import com.midokura.netlink.exceptions.NetlinkException;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public class Callback<T> {

    public void onSuccess(T data) {
    }

    public void onTimeout() {
    }

    public void onError(NetlinkException e) {

    }
}
