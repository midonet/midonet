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
package org.midonet.util.functors;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.util.functors.callbacks.AbstractCallback;
import org.midonet.util.functors.callbacks.AdaptingCallback;

/**
 * Some helper functions for Callbacks.
 */
public class Callbacks {

    private static final Logger log = LoggerFactory.getLogger(Callbacks.class);

    /**
     * It will get a target callback and a transformation from a source and it
     * will return a callback that can receive a source type,
     * apply the transformation and call the original callback.
     *
     * @param callback the callback to be wrapped
     * @param functor the transformation method for the data
     * @param <From> the source type
     * @param <To> the destination type
     * @param <E> the exception type
     *
     * @return a callback instance that can accept source types
     */
    public static <From, To, E extends Exception> Callback<From, E> 
        transform(@Nonnull final Callback<To, E> callback,
                  @Nonnull final Functor<From, To> functor) {
        return new AdaptingCallback<From, To, E>(callback, functor);
    }

    public static <
        T,
        E extends Exception,
        C extends Callback<Callback.MultiResult<T>, E>
        >
    ComposingCallback<T, E> composeTo(final C target) {
        return new DefaultComposingCallback<T, E>(target);
    }

    private static class DefaultComposingCallback<T, E extends Exception>
        extends AbstractCallback<Callback.MultiResult<T>, E>
        implements ComposingCallback<T, E> {

        AtomicInteger pendingEvents;
        Set<Callback.Result<T>> results;
        private final Callback<MultiResult<T>, E> target;
        volatile boolean enabled;
        volatile boolean lastTimeout;
        volatile E lastError;

        public DefaultComposingCallback(Callback<MultiResult<T>, E> target) {
            this.target = target;
            pendingEvents = new AtomicInteger(1);

            results = Collections.newSetFromMap(
                new ConcurrentHashMap<Callback.Result<T>, Boolean>());
        }

        @Override
        public Callback<T, E> createCallback(final String identifier) {
            Callback<T, E> callback = new Callback<T, E>() {
                @Override
                public void onSuccess(T data) {
                    registerResult(identifier, data);
                }

                @Override
                public void onTimeout() {
                    registerTimeout(identifier);
                }

                @Override
                public void onError(E e) {
                    registerError(identifier, e);
                }
            };

            pendingEvents.incrementAndGet();
            return callback;
        }

        @Override
        public <C extends Callback<T, E>> C createCallback(final String identifier, Class<C> type) {
            Object o =
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{type},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args)
                            throws Throwable {
                            String methodName = method.getName();

                            if (methodName.equals("onSuccess")) {
                                @SuppressWarnings("unchecked")
                                T res = (T) args[0]; // unsafe
                                registerResult(identifier, res);
                            } else if (methodName.equals("onTimeout")) {
                                registerTimeout(identifier);
                            } else if (methodName.equals("onError")) {
                                @SuppressWarnings("unchecked")
                                E res = (E) args[0]; // unsafe
                                registerError(identifier, res);
                            }

                            return null;
                        }
                    });

            pendingEvents.incrementAndGet();
            return type.cast(o);
        }

        @Override
        public void onSuccess(MultiResult<T> data) {
            target.onSuccess(data);
        }

        @Override
        public void onTimeout() {
            target.onTimeout();
        }

        @Override
        public void onError(E e) {
            target.onError(e);
        }

        private void registerResult(String identifier, T data) {
            results.add(new Result<T>(identifier, data, false, null));
            fireNewResult();
        }

        private void registerTimeout(String identifier) {
            results.add(new Result<T>(identifier, null, true, null));
            lastTimeout = true;
            fireNewResult();
        }

        private void registerError(String identifier, E error) {
            results.add(new Result<T>(identifier, null, false, error));
            lastError = error;
            fireNewResult();
        }

        @Override
        public void enableResultCollection() {
            enabled = true;
            fireNewResult();
        }

        private void fireNewResult() {
            int pendingEvents = this.pendingEvents.decrementAndGet();
            log.info("pending events: {}, enabled: {}", pendingEvents, enabled);
            if ( pendingEvents == 0 && enabled ) {
                if ( lastTimeout ) {
                    onTimeout();
                }

                if ( lastError != null) {
                    onError(lastError);
                }

                onSuccess(new MultiResult<T>() {
                    @Override
                    public Iterator<Callback.Result<T>> iterator() {
                        return results.iterator();
                    }

                    @Override
                    public boolean hasTimeouts() {
                        return lastTimeout;
                    }

                    @Override
                    public boolean hasExceptions() {
                        return lastError != null;
                    }
                });
            }
        }

        class Result<T> implements Callback.Result<T> {
            String op;
            T data;
            boolean timeout;
            E exception;

            public Result(String op, T data, boolean timeout, E exception) {
                this.op = op;
                this.data = data;
                this.timeout = timeout;
                this.exception = exception;
            }

            @Override
            public T getData() {
                return data;
            }

            @Override
            public boolean timeout() {
                return timeout;
            }

            @Override
            public Exception exception() {
                return exception;
            }

            @Override
            public String operation() {
                return op;
            }
        }
    }
}
