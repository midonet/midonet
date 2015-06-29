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

import java.util.Comparator;

import com.fasterxml.jackson.databind.introspect.AnnotatedField;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;

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
    public boolean isIgnorableField(AnnotatedField field) {
        Since s = field.getAnnotation(Since.class);
        Until u = field.getAnnotation(Until.class);

        return isIgnorable(s, u);
    }

    public boolean isIgnorableMethod(AnnotatedMethod method) {

        Since s = method.getAnnotation(Since.class);
        Until u = method.getAnnotation(Until.class);

        return isIgnorable(s, u);
    }

}

