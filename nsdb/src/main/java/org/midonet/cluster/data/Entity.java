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
package org.midonet.cluster.data;

/**
 * A common interface representing an entity in the cluster data model.
 */
public interface Entity<Id, Data, Self extends Entity<Id, Data, Self>> {

    public Id getId();

    public Self setId(Id id);

    public Data getData();

    public Self setData(Data data);

    public static abstract class Base<Id, Data, Self extends Base<Id, Data, Self>> implements Entity<Id, Data, Self> {
        Id id;
        Data data;

        protected Base(Id id, Data data) {
            this.id = id;
            this.data = data;
        }

        @Override
        public Id getId() {
            return id;
        }

        @Override
        public Self setId(Id id) {
            this.id = id;
            return self();
        }

        @Override
        public Data getData() {
            return data;
        }

        @Override
        public Self setData(Data data) {
            this.data = data;
            return self();
        }

        protected abstract Self self();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Base<?,?,?> base = (Base<?,?,?>) o;

            if (id != null ? !id.equals(base.id) : base.id != null)
                return false;

            if (data != null ? !data.equals(base.data) : base.data != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = id != null ? id.hashCode() : 0;
            result = 31 * result + (data != null ? data.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return self().getClass().getName() + "{" +
                "id=" + id +
                ", data=" + data +
                '}';
        }
    }

    public static interface TaggableEntity { }

}
