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
package org.midonet.brain.test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import rx.Observable;
import rx.Subscription;
import rx.functions.Action0;
import rx.functions.Action1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Some utilities to test observables.
 */
public class RxTestUtils {

    private interface EvaluableAction<T> extends Action1<T>, Action0 {
        public void evaluate();
    }

    public static class TestedObservable<T> {

        private final Observable<T> o;

        private EvaluableAction<T> onNext;
        private EvaluableAction<Throwable> onError;
        private EvaluableAction onComplete;

        private Subscription sub;
        private boolean isSubscribed;

        private TestedObservable(Observable<T> o) {
            this.o = o;
        }

        public TestedObservable<T> expect(T... elements) {
            onNext = new ActionAccumulator<>(elements);
            return this;
        }

        public TestedObservable<T> noElements() {
            onNext = new ActionRefuser<>();
            return this;
        }

        public TestedObservable<T> noErrors() {
            onError = new ActionRefuser<>();
            return this;
        }

        public TestedObservable<T> notCompleted() {
            onComplete = new ActionRefuser<>();
            return this;
        }

        public TestedObservable<T> completes() {
            onComplete = new ActionAccumulator(1);
            return this;
        }

        public TestedObservable subscribe() {
            sub = o.subscribe(onNext, onError, onComplete);
            this.isSubscribed = true;
            return this;
        }

        public TestedObservable evaluate() {
            onNext.evaluate();
            onError.evaluate();
            onComplete.evaluate();
            assertEquals(isSubscribed, !sub.isUnsubscribed());
            return this;
        }

        public void unsubscribe() {
            this.isSubscribed = false;
            sub.unsubscribe();
        }

    }

    public static <T> TestedObservable<T> test(Observable<T> obs) {
        return new TestedObservable<>(obs);
    }

    /**
     * Accumulates payload from callbacks.
     */
    public static class ActionAccumulator<T> implements EvaluableAction<T> {

        public final List<T> notifications = new ArrayList<>();
        public final List<T> expected;
        int numCalls = 0;
        int expectCalls = 0;

        public ActionAccumulator(int times) {
            this.expected = new ArrayList<>();
            this.expectCalls = times;
        }

        public ActionAccumulator(T... expected) {
            this.expected = Arrays.asList(expected);
            this.expectCalls = expected.length;
        }

        @Override
        public void call(T update) {
            notifications.add(update);
            numCalls++;
            if (expectCalls > 0 && notifications.size() > expectCalls) {
                fail("Expected at most " + this.expectCalls + " callbacks");
            }
        }

        @Override
        public void call() {
            numCalls++;
            if (expectCalls > 0 && notifications.size() > expectCalls) {
                fail("Expected at most " + this.expectCalls + " callbacks");
            }
        }

        @Override
        public void evaluate() {
            assertEquals(expectCalls, numCalls);
            if (!expected.isEmpty()) { // to support Action0 which calls but
                                       // doesn't add elements
                assertEquals(expected, notifications);
            }
        }

    }

    /**
     * Will fail the test as soon as it's called.
     *
     * @param <T>
     */
    public static class ActionRefuser<T> extends ActionAccumulator<T> {
        @Override
        public void call(T update) {
            fail("Didn't expect any callbacks but got " + update);
        }
        @Override
        public void call() {
            fail("Didn't expect any callbacks");
        }
        @Override
        public void evaluate() {
            assertEquals(0, this.numCalls);
        }
    }

}
