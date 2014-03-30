/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet;

public interface Subscription {
    boolean isUnsubscribed();
    void unsubscribe();
}
