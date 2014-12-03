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
package org.midonet.cluster;

import java.util.Objects;

public class TestUtil {

    public static class SimplePojo {

        public Integer id;
        public String value;

        public SimplePojo() {

        }

        public SimplePojo(Integer id, String value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            if (null == obj) return false;
            if (!(obj instanceof SimplePojo)) return false;
            SimplePojo o = (SimplePojo)obj;
            return Objects.equals(id, o.id) &&
                   Objects.equals(value, o.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, value);
        }
    }

}
