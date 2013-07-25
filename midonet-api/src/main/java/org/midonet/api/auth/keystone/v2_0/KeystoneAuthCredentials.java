/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.auth.keystone.v2_0;

import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.map.ObjectMapper;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlType;
import java.io.IOException;

/**
 * Keystone credentials DTO object
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class KeystoneAuthCredentials {

    private Auth auth;

    public KeystoneAuthCredentials(){
    }

    public KeystoneAuthCredentials(String username, String password,
                                   String tenantName){
        auth = new Auth(username, password, tenantName);
    }

    @XmlType(propOrder = {"passwordCredentials", "tenantName"})
    public static class Auth {

        private PasswordCredentials passwordCredentials;
        private String tenantName;

        public Auth(){
        }

        public Auth(String username, String password, String tenantName) {
            this.passwordCredentials = new PasswordCredentials(username,
                    password);
            this.tenantName = tenantName;
        }

        public static class PasswordCredentials {

            private String username;
            private String password;

            public PasswordCredentials() {
            }

            public PasswordCredentials(String username, String password) {
                this.username = username;
                this.password = password;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            @Override
            public String toString() {
                StringBuilder sb = new StringBuilder();
                sb.append("username: ");
                sb.append(this.username);
                return sb.toString();
            }
        }

        public String getTenantName() {
            return tenantName;
        }

        public void setTenantName(String tenantName) {
            this.tenantName = tenantName;
        }

        public PasswordCredentials getPasswordCredentials() {
            return passwordCredentials;
        }

        public void setPasswordCredentials(
                PasswordCredentials passwordCredentials) {
            this.passwordCredentials = passwordCredentials;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("tenantName: ");
            sb.append(this.tenantName);
            sb.append(", passwordCredentials: { ");
            sb.append(this.passwordCredentials);
            sb.append(" }");
            return sb.toString();
        }
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("auth: { ");
        sb.append(this.auth);
        sb.append(" }");
        return sb.toString();
    }

    @JsonIgnore
    public String toJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(this) ;
    }
}
