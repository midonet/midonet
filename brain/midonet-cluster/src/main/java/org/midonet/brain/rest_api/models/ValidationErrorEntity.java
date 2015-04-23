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
package org.midonet.brain.services.rest_api.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ValidationErrorEntity extends ErrorEntity {

    // Map of pairs <violated property, error msg>
    private List<Map<String, String>> violations = new ArrayList<>();

    public List<Map<String, String>> getViolations() {
        return violations;
    }

    public void setViolations(List<Map<String, String>> violations) {
        this.violations = violations;
    }
}
