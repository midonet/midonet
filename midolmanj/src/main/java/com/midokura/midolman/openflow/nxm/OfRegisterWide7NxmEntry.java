/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public class OfRegisterWide7NxmEntry extends IntNomaskNxmEntry {

    public OfRegisterWide7NxmEntry(int value) {
        super(value);
    }

    public OfRegisterWide7NxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_REGISTER_O;
    }
}
