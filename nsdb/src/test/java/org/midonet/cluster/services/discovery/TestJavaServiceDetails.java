/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.discovery;

import com.fasterxml.jackson.annotation.JsonRootName;

/**
 * Test class for service discovery details in java.
 * Java descriptions require, at least, the @JsonRootName annotation,
 * and a default constructor without parameters.
 * Methods 'equals' and 'hashCode' must be overwritten.
 */
@JsonRootName("details")
public class TestJavaServiceDetails {
    private String description = "";
    public TestJavaServiceDetails(String desc) {
        description = (desc == null)? "" : desc;
    }
    public TestJavaServiceDetails() {
        this("");
    }
    public void setDescription(String desc) {
        description = (desc == null)? "" : desc;
    }
    public String getDescription() {
        return description;
    }
    @Override
    public boolean equals(Object that) {
        return (that != null) && (that instanceof TestJavaServiceDetails) &&
               description.equals(((TestJavaServiceDetails)that).description);
    }
    @Override
    public int hashCode() {
        return description.hashCode();
    }
}
