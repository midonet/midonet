/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

import com.google.common.base.Predicate;
import static com.google.common.base.Predicates.and;

import com.midokura.midonet.client.resource.Router;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/22/12
 * Time: 11:54 PM
 *
 * This is EXPERIMENTAL
 *
 */
public class RouterPredicates implements Predicate<Router> {

    private final List<Predicate<Router>> predicates;

    public RouterPredicates(Builder b) {
        predicates = b.predicates;
    }

    public static Predicate<Router> byName(final String name) {
        return new Predicate<Router>() {
            @Override
            public boolean apply(@Nullable Router input) {
                return input != null && input.getName().equals(name);
            }
        };
    }

    public static Predicate<Router> byId(final UUID id) {
        return new Predicate<Router>() {
            @Override
            public boolean apply(@Nullable Router input) {
                return input != null && input.getId().equals(id);
            }
        };
    }

    @Override
    public boolean apply(@Nullable Router input) {
        return and(predicates).apply(input);
    }


    public static class Builder {

        List<Predicate<Router>> predicates = new ArrayList<Predicate<Router>>();


        public Builder name(final String name) {
            predicates.add(
                    new Predicate<Router>() {
                        @Override
                        public boolean apply(@Nullable Router input) {
                            return input != null &&
                                    input.getName().equals(name);
                        }
                    });
            return this;
        }


        public Builder id(final UUID id) {
            predicates.add(
                    new Predicate<Router>() {
                        @Override
                        public boolean apply(@Nullable Router input) {
                            return input != null &&
                                    input.getId().equals(id);
                        }
                    });
            return this;
        }

        public Predicate<Router> build() {
            return new RouterPredicates(this);
        }
    }


}
