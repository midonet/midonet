/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.state;

import javax.annotation.Nonnull;

import org.apache.zookeeper.KeeperException;

import com.midokura.util.functors.Functor;

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
        extends com.midokura.util.functors.callbacks.AdaptingCallback<
                DirectoryCallback.Result<From>, DirectoryCallback.Result<To>,
                KeeperException>
        implements DirectoryCallback<From> {

        private AdaptingCallback(DirectoryCallback<To> target,
                                 final Functor<From, To> adaptor) {
            super(target,
                new Functor<DirectoryCallback.Result<From>,
                            DirectoryCallback.Result<To>>() {
                    @Override
                    public DirectoryCallback.Result<To> apply(
                                DirectoryCallback.Result<From> arg0) {
                        return new DirectoryCallback.Result<To>(
                            adaptor.apply(arg0.getData()), arg0.getStat());
                    }
                });
        }
    }
}
