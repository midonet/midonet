/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public class OfRegisterWide3NxmEntry extends IntNomaskNxmEntry {

    public OfRegisterWide3NxmEntry(int value) {
        super(value);
    }

    public OfRegisterWide3NxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_REGISTER_W_3;
    }
}
