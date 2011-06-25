/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.openflow;

public interface SuccessHandler<T> {

    void onSuccess(T data);

}
