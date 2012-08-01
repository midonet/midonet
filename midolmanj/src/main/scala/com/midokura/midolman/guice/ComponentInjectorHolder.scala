/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice

import com.google.inject.Injector

/**
 * // TODO: mtoader ! Please explain yourself.
 */
object ComponentInjectorHolder {

    var injector: Injector = null

    def setInjector(injector: Injector) {
        this.injector = injector
    }

    def inject(obj: AnyRef) {
        if (injector != null)
            injector.injectMembers(obj)
    }
}


