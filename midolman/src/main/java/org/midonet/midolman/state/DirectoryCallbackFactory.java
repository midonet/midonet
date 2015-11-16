/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.state;

import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;

import rx.Observer;

import org.apache.zookeeper.KeeperException;

import org.midonet.cluster.backend.DirectoryCallback;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.util.functors.Functor;

/**
 * Factory method for Directory callback.
 */
public class DirectoryCallbackFactory {

    public static <From, To> DirectoryCallback<From>
        transform(@Nonnull final DirectoryCallback<To> callback,
                  @Nonnull final Functor<From, To> functor) {
        return new AdaptingCallback<>(callback, functor);
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

    public static <T> DirectoryCallback<T> wrap(final Observer<? super T> obs) {
        return new DirectoryCallback<T>() {
            @Override
            public void onSuccess(T value) {
                obs.onNext(value);
                obs.onCompleted();
            }
            @Override
            public void onTimeout() {
                obs.onError(new StateAccessException(new TimeoutException()));
            }

            @Override
            public void onError(KeeperException ke) {
                obs.onError(new StateAccessException(ke));
            }
        };
    }

}
