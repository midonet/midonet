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
