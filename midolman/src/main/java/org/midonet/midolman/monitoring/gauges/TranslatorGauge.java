/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.monitoring.gauges;

import com.yammer.metrics.core.Gauge;

import org.midonet.util.functors.Functor;

/**
 * It's a Gauge implementation that can translate an underlying Gauge's result
 * into a different type by applying a transformer function.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/31/12
 */
public class TranslatorGauge<F, T> extends Gauge<T> {

    private final Gauge<F> source;
    private final Functor<F, T> functor;

    public TranslatorGauge(Gauge<F> source, Functor<F, T> functor) {
        this.source = source;
        this.functor = functor;
    }

    @Override
    public T value() {
        return functor.apply(source.value());
    }
}
