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
package org.midonet.midolman.version.serialization;

import java.util.Comparator;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.cluster.serialization.JsonVersionZkSerializer;
import org.midonet.midolman.serialization.SerializationException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

@RunWith(MockitoJUnitRunner.class)
public class TestJsonVersionZkSerializer {

    private JsonVersionZkSerializer testObject;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private Comparator<String> versionComparator;

    @Mock(answer = Answers.RETURNS_SMART_NULLS)
    private SystemDataProvider systemDataProvider;

    // Use these constants for convenience
    private static final String DUMMY_VERSION = "dummy_version";
    private static final String CONCRETE_CLASS = "concrete_class";

    // Test classes that can be used to test polymorphism as well as simple
    // (de)serializations
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({@JsonSubTypes.Type(value = TestConcrete.class,
            name = CONCRETE_CLASS)})
    private static abstract class TestAbstract {

        private String abstractField;

        public TestAbstract(){
        }

        public String getAbstractField() {
            return abstractField;
        }

        public void setAbstractField(String abstractField) {
            this.abstractField = abstractField;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null) return false;

            TestAbstract o = (TestAbstract) other;
            if (abstractField == null && o.getAbstractField() != null)
                return false;
            if (abstractField != null && o.getAbstractField() == null)
                return false;

            return (this.abstractField.equals(o.getAbstractField()));
        }
    }

    private static class TestConcrete extends TestAbstract {

        private String concreteField;

        public TestConcrete(){
        }

        public String getConcreteField() {
            return concreteField;
        }

        public void setConcreteField(String concreteField) {
            this.concreteField = concreteField;
        }

        @Override
        public boolean equals(Object other) {

            if (!super.equals(other)) return false;

            TestConcrete o = (TestConcrete) other;
            if (concreteField == null && o.getConcreteField() != null)
                return false;
            if (concreteField != null && o.getConcreteField() == null)
                return false;
            return (this.concreteField.equals(o.getConcreteField()));
        }
    }

    private byte[] getVersionConfigTestConcreteJson(TestConcrete obj) {

        // Perhaps it might be better to test the output of serialization, but
        // this forces you to fully understand the output of VersionConfig.

        String innerObjStr = String.format(
                "{\"type\":\"%s\"" +
                        ",\"abstractField\":\"%s\"" +
                        ",\"concreteField\":\"%s\"}",
                CONCRETE_CLASS, obj.getAbstractField(), obj.getConcreteField());

        String versionConfigStr = String.format(
                "{\"data\":%s,\"version\":\"%s\"}", innerObjStr, DUMMY_VERSION);

        return versionConfigStr.getBytes();
    }

    @Before
    public void setUp() throws Exception {

        // Use spy so that we can stub 'getObjectMapper'
        testObject = spy(new JsonVersionZkSerializer(systemDataProvider,
               versionComparator));

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        doReturn(mapper).when(testObject).getObjectMapper(DUMMY_VERSION);
        doReturn(DUMMY_VERSION).when(systemDataProvider).getWriteVersion();
    }

    @Test
    public void testSerializeWithGeneric() throws SerializationException {

        TestConcrete input = new TestConcrete();
        input.setAbstractField("bar");
        input.setConcreteField("baz");

        byte[] expected = getVersionConfigTestConcreteJson(input);

        byte[] actual = testObject.serialize(input);

        assertArrayEquals(expected, actual);
    }

    @Test
    public void testDeserializeWithGeneric() throws SerializationException {

        TestConcrete expected = new TestConcrete();
        expected.setAbstractField("bar");
        expected.setConcreteField("baz");

        byte[] input = getVersionConfigTestConcreteJson(expected);

        // Polymorphism should create TestConcrete
        TestAbstract actual = testObject.deserialize(input,
                TestAbstract.class);

        assertEquals(expected, actual);
    }
}
