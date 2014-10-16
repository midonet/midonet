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
package org.midonet.api.auth.cloudstack;

/**
 * Response from GetUser command
 */
public class CloudStackUser {

    public static String STATE_DISABLED = "disabled";

    private String id;
    private String name;
    private String accountId;
    private String account;
    private String apiKey;
    private String state;
    private int accountType = -1;

    public CloudStackUser(){
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public int getAccountType() {
        return accountType;
    }

    public void setAccountType(int accountType) {
        this.accountType = accountType;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    /**
     * Check if the user is disabled
     * @return True if the user is disabled.  False if not.
     */
    public boolean isDisabled() {
        return this.state != null && this.state.equals(STATE_DISABLED);
    }

    /**
     * Check if the user is admin
     * @return True if the user is admin.  False if not.
     */
    public boolean isAdmin() {
        return this.accountType == 1;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("id=");
        sb.append(this.id);
        sb.append(", name=");
        sb.append(this.name);
        sb.append(", accountId=");
        sb.append(this.accountId);
        sb.append(", account=");
        sb.append(this.account);
        sb.append(", accountType=");
        sb.append(accountType);
        sb.append(", state=");
        sb.append(state);
        sb.append(", apiKey=");
        sb.append(apiKey);
        return sb.toString();
    }

}
