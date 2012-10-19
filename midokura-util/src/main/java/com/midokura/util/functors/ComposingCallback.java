/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.functors;

/**
 *
 */
public interface ComposingCallback<T, E extends Exception>
    extends Callback<Callback.MultiResult<T>, E> {

    public Callback<T, E> createCallback(String identifier);

    public <C extends Callback<T, E>> C createCallback(String identifier, Class<C> type);

    public void enableResultCollection();

}
