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
package org.midonet.util.version;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotationMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@RunWith(Enclosed.class)
public class TestVersionCheckAnnotationIntrospector {

    public static VersionCheckAnnotationIntrospector testObject;

    public static Comparator<String> mockComparator() {
        @SuppressWarnings("unchecked")                      /* safe since it */
        Comparator<String> comp = mock(Comparator.class);   /* is a mock obj */
        return comp;
    }

    private static class TestClass {

        public String nonAnnotated;

        @Since("since_version")
        public String sinceOnly;

        @Until("until_version")
        public String untilOnly;

        @Since("since_version")
        @Until("until_version")
        public String sinceAndUntil;
    }

    private static AnnotatedField getAnnotatedField(String fieldName)
            throws NoSuchFieldException {

        Field field = TestClass.class.getField(fieldName);
        AnnotatedClass clazz = AnnotatedClass.construct(TestClass.class, testObject, null);
        AnnotationMap map = new AnnotationMap();
        for (Annotation annotation : field.getAnnotations()) {
            map.add(annotation);
        }
        return new AnnotatedField(clazz, field, map);
    }

    private static void setComparator(Comparator<String> comparator,
                                      String v1,
                                      String v2,
                                      int result) {
        if (result > 0) {
            doReturn(result).when(comparator).compare(v1, v2);
            doReturn(-1).when(comparator).compare(v2, v1);
        } else if (result < 0) {
            doReturn(result).when(comparator).compare(v1, v2);
            doReturn(1).when(comparator).compare(v2, v1);
        } else {
            doReturn(0).when(comparator).compare(v1, v2);
            doReturn(0).when(comparator).compare(v2, v1);
        }
    }

    /**
     * Test annotation.
     */
    @RunWith(Parameterized.class)
    public static class TestIsIgnorableFieldWithComparator {

        private final AnnotatedField annotatedFieldInput;
        private final Comparator<String> comparatorInput;
        private final boolean expected;
        private static final String RUNNING_VERSION = "running_version";

        public TestIsIgnorableFieldWithComparator(
                AnnotatedField annotatedFieldInput,Comparator<String> comparatorInput,
                boolean expected) {
            this.annotatedFieldInput = annotatedFieldInput;
            this.comparatorInput = comparatorInput;
            this.expected = expected;
        }

        @Parameters
        public static Collection<Object[]> inputs()
                throws NoSuchFieldException {

            List<Object[]> params = new ArrayList<Object[]>();

            // Test when both since and until are not specified
            AnnotatedField nullAnnotatedField =
                    getAnnotatedField("nonAnnotated");
            params.add(new Object[]{nullAnnotatedField,
                    mockComparator(), false});

            // Test when Since exists but Until does not
            AnnotatedField onlySinceAnnotatedFieldBefore =
                    getAnnotatedField("sinceOnly");

            // Version is less than since
            Comparator<String> lessThanSinceComp = mockComparator();
            setComparator(lessThanSinceComp, "since_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    lessThanSinceComp, true});

            // Version is equal to since
            Comparator<String> equalToSinceComp = mockComparator();
            setComparator(equalToSinceComp, "since_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    equalToSinceComp, false});

            // Version is greater than since
            Comparator<String> greaterThanSinceComp = mockComparator();
            setComparator(greaterThanSinceComp, "since_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    greaterThanSinceComp, false});

            // Test when Until exists but Since does not
            AnnotatedField onlyUntilAnnotatedFieldBefore =
                    getAnnotatedField("untilOnly");

            // Version is greater than until
            Comparator<String> greaterThanUntilComp = mockComparator();
            setComparator(greaterThanUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    greaterThanUntilComp, true});

            // Version is equal to since
            Comparator<String> equalToUntilComp = mockComparator();
            setComparator(equalToUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    equalToUntilComp, false});

            // Version is greater than since
            Comparator<String> lessThanUntilComp = mockComparator();
            setComparator(lessThanUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    lessThanUntilComp, false});

            // Test when Since and Until both exist
            AnnotatedField bothAnnotatedFieldBefore =
                    getAnnotatedField("sinceAndUntil");

            // Version is less than since
            Comparator<String> lessThanSinceUntilComp = mockComparator();
            setComparator(lessThanSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(lessThanSinceUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    lessThanSinceUntilComp, true});

            // Version is between since and until
            Comparator<String> betweenSinceUntilComp = mockComparator();
            setComparator(betweenSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(betweenSinceUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    betweenSinceUntilComp, false});

            // Version is equal to both since and until
            Comparator<String> equalsSinceUntilComp = mockComparator();
            setComparator(equalsSinceUntilComp, "since_version",
                    RUNNING_VERSION, 0);
            setComparator(equalsSinceUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    equalsSinceUntilComp, false});

            // Version is equal to since but less than until
            Comparator<String> equalsSinceLessUntilComp = mockComparator();
            setComparator(equalsSinceLessUntilComp, "since_version",
                    RUNNING_VERSION, 0);
            setComparator(equalsSinceLessUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    equalsSinceLessUntilComp, false});

            // Version is greater than since and equals to until
            Comparator<String> greaterSinceEqualsUntilComp = mockComparator();
            setComparator(greaterSinceEqualsUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(greaterSinceEqualsUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    greaterSinceEqualsUntilComp, false});

            // Version is greater than until and since
            Comparator<String> greaterSinceUntilComp = mockComparator();
            setComparator(greaterSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(greaterSinceUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    greaterSinceUntilComp, true});

            // Version is less than since but greater than until (error case)
            Comparator<String> lessSinceGreaterUntil = mockComparator();
            setComparator(lessSinceGreaterUntil, "since_version",
                    RUNNING_VERSION, 1);
            setComparator(lessSinceGreaterUntil, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    lessSinceGreaterUntil, true});

            return params;
        }

        @Test
        public void testIsIgnorable() {
            testObject = new VersionCheckAnnotationIntrospector(
                    RUNNING_VERSION, this.comparatorInput);

            boolean actual = testObject.isIgnorableField(
                    this.annotatedFieldInput);

            assertEquals(this.expected, actual);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestIsIgnorableField {

        private final AnnotatedField annotatedFieldInput;
        private final String versionInput;
        private final boolean expected;

        public TestIsIgnorableField(AnnotatedField annotatedFieldInput,
                                    String versionInput, boolean expected) {
            this.annotatedFieldInput = annotatedFieldInput;
            this.versionInput = versionInput;
            this.expected = expected;
        }

        @Parameters
        public static Collection<Object[]> inputs()
                throws NoSuchFieldException {

            List<Object[]> params = new ArrayList<Object[]>();

            // Test when both since and until are not specified
            AnnotatedField nullAnnotatedField =
                    getAnnotatedField("nonAnnotated");
            params.add(new Object[]{nullAnnotatedField, "a", false});

            // Test when Since exists but Until does not
            AnnotatedField onlySinceAnnotatedFieldBefore =
                    getAnnotatedField("sinceOnly");
            params.add(new Object[]{onlySinceAnnotatedFieldBefore, "r", true});
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    "since_version", false});
            params.add(new Object[]{onlySinceAnnotatedFieldBefore, "t",
                    false});

            // Test when Until exists but Since does not
            AnnotatedField onlyUntilAnnotatedFieldBefore =
                    getAnnotatedField("untilOnly");
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore, "t",
                    false});
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    "until_version", false});
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore, "v",
                    true});

            // Test when Since and Until both exist
            AnnotatedField bothAnnotatedFieldBefore =
                    getAnnotatedField("sinceAndUntil");
            params.add(new Object[]{bothAnnotatedFieldBefore, "r", true});
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    "since_version", false});
            params.add(new Object[]{bothAnnotatedFieldBefore, "t", false});
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    "until_version", false});
            params.add(new Object[]{bothAnnotatedFieldBefore, "v", true});

            return params;
        }

            @Before
        public void setup() {
            testObject = new VersionCheckAnnotationIntrospector(versionInput);
        }

        @Test
        public void testIsIgnorable() {

            boolean actual = testObject.isIgnorableField(
                    this.annotatedFieldInput);

            assertEquals(this.expected, actual);
        }
    }
}
