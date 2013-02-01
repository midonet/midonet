/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.functors.callbacks;

import com.midokura.util.functors.Callback;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class AbstractCallback<T, E extends Exception>
    implements Callback<T, E> {

    @Override
    public void onSuccess(T data) {
    }

    @Override
    public void onTimeout() {
    }

    @Override
    public void onError(E e) {
    }
}
