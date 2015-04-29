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
