/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.management;

import java.beans.ConstructorProperties;
import java.io.Serializable;
import java.util.Map;
import java.util.Objects;

public final class FlowMeters implements Serializable {

    private final int index;
    private final Map<String, FlowStats> meters;

    @ConstructorProperties({"index", "meters"})
    public FlowMeters(int index, Map<String, FlowStats> meters) {
        this.index = index;
        this.meters = meters;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        if (o == this) return true;
        if (!(o instanceof FlowMeters)) return false;
        FlowMeters that = (FlowMeters)o;
        return index == that.index && Objects.equals(meters, that.meters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, meters);
    }

    public int getIndex() {
        return index;
    }

    public Map<String, FlowStats> getMeters() {
        return meters;
    }
}
