/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class TestNeutron {

    @Test
    @Parameters(source = NeutronClassProvider.class, method="neutronClasses")
    public <T> void testEqualsContract(Class<T> input) {

        // Ignore inheritance or mutable field errors because the tested
        // classes may be auto-generated code.
        EqualsVerifier.forClass(input).suppress(
                Warning.STRICT_INHERITANCE, Warning.NONFINAL_FIELDS).verify();
    }

}
