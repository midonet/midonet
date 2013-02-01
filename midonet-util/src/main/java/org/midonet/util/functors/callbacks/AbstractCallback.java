/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.functors.callbacks;

import org.midonet.util.functors.Callback;

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
