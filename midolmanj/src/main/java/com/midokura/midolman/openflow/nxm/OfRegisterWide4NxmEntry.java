/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.openflow.nxm;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/3/12
 */
public class OfRegisterWide4NxmEntry extends IntNomaskNxmEntry {

    public OfRegisterWide4NxmEntry(int value) {
        super(value);
    }

    public OfRegisterWide4NxmEntry() {
    }

    @Override
    public NxmType getNxmType() {
        return NxmType.NXM_REGISTER_O;
    }
}
