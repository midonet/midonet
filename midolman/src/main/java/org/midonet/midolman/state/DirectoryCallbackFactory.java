/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.state;

import javax.annotation.Nonnull;

import org.apache.zookeeper.KeeperException;

import org.midonet.util.functors.Functor;

/**
 * Factory method for Directory callback.
 */
public class DirectoryCallbackFactory {

    public static <From, To> DirectoryCallback<From>
        transform(@Nonnull final DirectoryCallback<To> callback,
                  @Nonnull final Functor<From, To> functor) {
        return new AdaptingCallback<From, To>(callback, functor);
    }

    private static class AdaptingCallback<From, To>
        extends org.midonet.util.functors.callbacks.AdaptingCallback<From,To, KeeperException>
        implements DirectoryCallback<From> {

        private AdaptingCallback(DirectoryCallback<To> target,
                                 final Functor<From, To> adaptor) {
            super(target,
                new Functor<From,To>() {
                    @Override
                    public To apply(From input) {
                        return adaptor.apply(input);
                    }
                });
        }
    }
}
