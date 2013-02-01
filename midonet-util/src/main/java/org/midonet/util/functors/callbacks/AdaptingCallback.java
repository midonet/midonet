/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.functors.callbacks;

import org.midonet.util.functors.Callback;
import org.midonet.util.functors.Functor;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class AdaptingCallback<From, To, E extends Exception> implements Callback<From, E> {

    Callback<To, E> target;
    private Functor<From, To> functor;

    public AdaptingCallback(Callback<To, E> target, Functor<From, To> adaptor) {
        this.target = target;
        this.functor = adaptor;
    }

    @Override
    public void onSuccess(From data) {
        target.onSuccess(functor.apply(data));
    }

    @Override
    public void onTimeout() {
        target.onTimeout();
    }

    @Override
    public void onError(E e) {
        target.onError(e);
    }
}
