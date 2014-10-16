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
package org.midonet.api.auth.keystone.v2_0;

import javax.print.attribute.standard.DateTimeAtCompleted;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.Date;
import java.util.List;

/**
 * Keystone access DTO
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class KeystoneAccess {

    private Access access;

    public static class Access {

        private Token token;
        private User user;

        public static class Token {
            private String id;
            private Tenant tenant;
            private String expires;

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public Tenant getTenant() {
                return tenant;
            }

            public void setTenant(Tenant tenant) {
                this.tenant = tenant;
            }

            public String getExpires() {
                return expires;
            }

            public void setExpires(String expires) {
                this.expires = expires;
            }

            public static class Tenant {
                private String id;
                private String name;

                public String getId() {
                    return id;
                }

                public void setId(String id) {
                    this.id = id;
                }

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }
            }
        }

        public static class User {
            private String id;
            private List<Role> roles;

            public static class Role {
                private String name;

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }
            }

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public List<Role> getRoles() {
                return roles;
            }

            public void setRoles(List<Role> roles) {
                this.roles = roles;
            }
        }

        public Token getToken() {
            return token;
        }

        public void setToken(Token token) {
            this.token = token;
        }

        public User getUser() {
            return user;
        }

        public void setUser(User user) {
            this.user = user;
        }
    }

    public Access getAccess() {
        return access;
    }

    public void setAccess(Access access) {
        this.access = access;
    }
}
