/*
 * Copyright (c) 2012. Midokura Japan K.K.
 */

package com.midokura.midonet.client.resource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import com.google.common.base.Predicate;
import com.google.common.collect.ForwardingList;
import com.google.common.collect.Iterables;

/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 8/16/12
 * Time: 4:36 PM
 */
public class ResourceCollection<E> extends ForwardingList<E> {

    final List<E> delegate;

    public ResourceCollection(List<E> l) {
        this.delegate = l;
    }

    @Override
    protected List<E> delegate() {
        return delegate;
    }

    /**
     * Finds a resource by the given key and value for the resource model.
     *
     * @param key   attribute name (case insensitive)
     * @param value value to look for
     * @return First matched element
     */
    @Deprecated // TODO: let client use find() method defined below.
    public E findBy(final String key, final Object value) {
        final String keyGetter = ("get" + key).toLowerCase();
        // this extends forwarding list, therefore should be Iterable.
        @SuppressWarnings("unchecked")
        E result = Iterables.find((Iterable<E>) this, new Predicate<E>() {
            @Override
            public boolean apply(E input) {
                Method[] methods = input.getClass().getMethods();
                for (Method m : methods) { // O(n) but should be small.
                    if (m.getName().toLowerCase().equals(keyGetter)) {
                        try {
                            //Getter method of the value should return
                            @SuppressWarnings("unchecked")
                            Object data = m.invoke(input, new Object[]{});
                            if (data == null) {
                                return data == value;
                            }
                            return data.equals(value);
                        } catch (IllegalAccessException e) {
                            e.printStackTrace();
                        } catch (InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    }
                }
                throw new IllegalArgumentException("No matched entry found " +
                        "for searching key=" + key + "value=" + value);
            }
        });
        return result;
    }

    /**
     * Forwarding method to Iterables.find in Guava
     *
     * @param predicate
     * @return
     */
    public E find(Predicate<? super E> predicate) {
        return Iterables.find(delegate, predicate);
    }
}
