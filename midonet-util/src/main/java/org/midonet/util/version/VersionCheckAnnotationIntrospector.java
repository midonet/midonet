/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.util.version;

import org.codehaus.jackson.map.introspect.AnnotatedMember;
import org.codehaus.jackson.map.introspect.AnnotatedField;
import org.codehaus.jackson.map.introspect.AnnotatedMethod;
import org.codehaus.jackson.map.introspect.AnnotatedConstructor;
import org.codehaus.jackson.map.introspect.NopAnnotationIntrospector;

import java.lang.annotation.Annotation;
import java.util.Comparator;

/**
 * Filters fields to be serialized/deserialized based on Annotations
 * created for versioning. This is called by the Jackson serialization
 * infrastructure to determine which fields to filter out based on version.
 */
public class VersionCheckAnnotationIntrospector
        extends NopAnnotationIntrospector {

    // the version of this midonet
    private final String runningVersion;

    // Custom comparator in case you want to override the default Comparable
    // behavior.
    private final Comparator<String> versionComparator;

    public VersionCheckAnnotationIntrospector(
            String runningVersion, Comparator<String> vsnComparator) {
        this.runningVersion = runningVersion;
        this.versionComparator = vsnComparator;
    }

    public VersionCheckAnnotationIntrospector(String runningVersion) {
        this(runningVersion, null);
    }

    @Override
    public boolean hasIgnoreMarker(AnnotatedMember m){
        if (m instanceof AnnotatedMethod) {
            return isIgnorableMethod((AnnotatedMethod) m);
        }
        if (m instanceof AnnotatedField) {
            return isIgnorableField((AnnotatedField) m);
        }
        if (m instanceof AnnotatedConstructor) {
            return isIgnorableConstructor((AnnotatedConstructor) m);
        }
        return false;
    }

    private int compare(String val1, String val2) {
        if (versionComparator != null) {
            return versionComparator.compare(val1, val2);
        } else {
            return val1.compareTo(val2);
        }
    }

    private boolean isIgnorable(Since s, Until u) {
        // Check if version is before Since
        if (s != null && compare(s.value(), this.runningVersion) > 0) {
            return true;
        }
        // Check if version is after until
        if (u != null && compare(u.value(), this.runningVersion) < 0) {
            return true;
        }
        return false;
    }

    /**
     * returns false if we want to serialize the field,
     * true if we want to filter it out.
     */
    @Override
    public boolean isIgnorableField(AnnotatedField field) {
        Since s = field.getAnnotation(Since.class);
        Until u = field.getAnnotation(Until.class);

        return isIgnorable(s, u);
    }

    @Override
    public boolean isIgnorableMethod(AnnotatedMethod method) {

        Since s = method.getAnnotation(Since.class);
        Until u = method.getAnnotation(Until.class);

        return isIgnorable(s, u);
    }

    /**
     * returns false if this annotationInspector can't handle the
     * annotation, true if we can.
     */
    @Override
    public boolean isHandled(Annotation ann) {
        Class<?> clazz = ann.annotationType();  /* safe to cast to ? since   */
        return (Since.class.equals(clazz) ||    /* the method checks handled */
                Until.class.equals(clazz));     /* Annotations types         */
    }
}

