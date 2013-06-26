/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.util.version;

import org.codehaus.jackson.map.introspect.AnnotatedField;
import org.codehaus.jackson.map.introspect.AnnotationMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@RunWith(Enclosed.class)
public class TestVersionCheckAnnotationIntrospector {

    public static VersionCheckAnnotationIntrospector testObject;

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
        AnnotationMap map = new AnnotationMap();
        for (Annotation annotation : field.getAnnotations()) {
            map.add(annotation);
        }
        return new AnnotatedField(field, map);
    }

    private static void setComparator(Comparator comparator,
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
    @interface TestAnnotation {}

    @RunWith(Parameterized.class)
    public static class TestIsHandled {

        private final Annotation input;
        private final boolean expected;

        public TestIsHandled(Annotation input, boolean expected) {
            this.input = input;
            this.expected = expected;
        }

        @Before
        public void setup() {
            testObject = new VersionCheckAnnotationIntrospector(
                    "dummy_version");
        }

        @Parameters
        public static Collection<Object[]> inputs() {

            List<Object[]> params = new ArrayList<Object[]>();

            Annotation mockNull = mock(Annotation.class);
            doReturn(null).when(mockNull).annotationType();
            params.add(new Object[]{mockNull, false});

            Annotation mockSince = mock(Annotation.class);
            doReturn(Since.class).when(mockSince).annotationType();
            params.add(new Object[] { mockSince, true });

            Annotation mockUntil = mock(Annotation.class);
            doReturn(Until.class).when(mockUntil).annotationType();
            params.add(new Object[]{mockUntil, true});

            Annotation mockTest = mock(Annotation.class);
            doReturn(TestAnnotation.class).when(mockTest).annotationType();
            params.add(new Object[]{mockTest, false});

            return params;
        }

        @Test
        public void testIsHandled() {
            boolean actual = testObject.isHandled(this.input);
            assertEquals(this.expected, actual);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestIsIgnorableFieldWithComparator {

        private final AnnotatedField annotatedFieldInput;
        private final Comparator comparatorInput;
        private final boolean expected;
        private static final String RUNNING_VERSION = "running_version";

        public TestIsIgnorableFieldWithComparator(
                AnnotatedField annotatedFieldInput,Comparator comparatorInput,
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
                    mock(Comparator.class), false});

            // Test when Since exists but Until does not
            AnnotatedField onlySinceAnnotatedFieldBefore =
                    getAnnotatedField("sinceOnly");

            // Version is less than since
            Comparator lessThanSinceComp = mock(Comparator.class);
            setComparator(lessThanSinceComp, "since_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    lessThanSinceComp, true});

            // Version is equal to since
            Comparator equalToSinceComp = mock(Comparator.class);
            setComparator(equalToSinceComp, "since_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    equalToSinceComp, false});

            // Version is greater than since
            Comparator greaterThanSinceComp = mock(Comparator.class);
            setComparator(greaterThanSinceComp, "since_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{onlySinceAnnotatedFieldBefore,
                    greaterThanSinceComp, false});

            // Test when Until exists but Since does not
            AnnotatedField onlyUntilAnnotatedFieldBefore =
                    getAnnotatedField("untilOnly");

            // Version is greater than until
            Comparator greaterThanUntilComp = mock(Comparator.class);
            setComparator(greaterThanUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    greaterThanUntilComp, true});

            // Version is equal to since
            Comparator equalToUntilComp = mock(Comparator.class);
            setComparator(equalToUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    equalToUntilComp, false});

            // Version is greater than since
            Comparator lessThanUntilComp = mock(Comparator.class);
            setComparator(lessThanUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{onlyUntilAnnotatedFieldBefore,
                    lessThanUntilComp, false});

            // Test when Since and Until both exist
            AnnotatedField bothAnnotatedFieldBefore =
                    getAnnotatedField("sinceAndUntil");

            // Version is less than since
            Comparator lessThanSinceUntilComp = mock(Comparator.class);
            setComparator(lessThanSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(lessThanSinceUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    lessThanSinceUntilComp, true});

            // Version is between since and until
            Comparator betweenSinceUntilComp = mock(Comparator.class);
            setComparator(betweenSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(betweenSinceUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    betweenSinceUntilComp, false});

            // Version is equal to both since and until
            Comparator equalsSinceUntilComp = mock(Comparator.class);
            setComparator(equalsSinceUntilComp, "since_version",
                    RUNNING_VERSION, 0);
            setComparator(equalsSinceUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    equalsSinceUntilComp, false});

            // Version is equal to since but less than until
            Comparator equalsSinceLessUntilComp = mock(Comparator.class);
            setComparator(equalsSinceLessUntilComp, "since_version",
                    RUNNING_VERSION, 0);
            setComparator(equalsSinceLessUntilComp, "until_version",
                    RUNNING_VERSION, 1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    equalsSinceLessUntilComp, false});

            // Version is greater than since and equals to until
            Comparator greaterSinceEqualsUntilComp = mock(Comparator.class);
            setComparator(greaterSinceEqualsUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(greaterSinceEqualsUntilComp, "until_version",
                    RUNNING_VERSION, 0);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    greaterSinceEqualsUntilComp, false});

            // Version is greater than until and since
            Comparator greaterSinceUntilComp = mock(Comparator.class);
            setComparator(greaterSinceUntilComp, "since_version",
                    RUNNING_VERSION, -1);
            setComparator(greaterSinceUntilComp, "until_version",
                    RUNNING_VERSION, -1);
            params.add(new Object[]{bothAnnotatedFieldBefore,
                    greaterSinceUntilComp, true});

            // Version is less than since but greater than until (error case)
            Comparator lessSinceGreaterUntil = mock(Comparator.class);
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
