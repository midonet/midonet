package com.midokura.midolman.openflow.nxm;

import java.nio.ByteBuffer;

/* This command enables or disables an Open vSwitch extension that allows a
 * controller to specify the OpenFlow table to which a flow should be added,
 * instead of having the switch decide which table is most appropriate as
 * required by OpenFlow 1.0.  By default, the extension is disabled.
 *
 * When this feature is enabled, Open vSwitch treats struct ofp_flow_mod's
 * 16-bit 'command' member as two separate fields.  The upper 8 bits are used
 * as the table ID, the lower 8 bits specify the command as usual.  A table ID
 * of 0xff is treated like a wildcarded table ID.
 */
public class NxSetFlowModCommandTableId extends NxMessage {

    final boolean enable;
    
    public NxSetFlowModCommandTableId(boolean enable) {
        super(15); // NXT_FLOW_MOD_TABLE_ID
        
        this.enable = enable;
        
        super.setLength((short) 24);
    }

    @Override
    public void writeTo(ByteBuffer data) {
        super.writeTo(data);
        
        data.put(enable ? (byte) 1 : (byte) 0);
        
        // 7 bytes of padding
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
        data.put((byte) 0);
    }

}
