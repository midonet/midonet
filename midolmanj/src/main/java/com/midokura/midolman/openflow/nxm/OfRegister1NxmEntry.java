/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public class OfRegister1NxmEntry extends IntNomaskNxmEntry {

    public OfRegister1NxmEntry(int value) {
        super(value);
    }

    public OfRegister1NxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_REGISTER_O;
    }
}
