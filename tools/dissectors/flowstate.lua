-- dissector for nsdb/src/main/resources/flowstate.schema.xml
-- usage:
--   tshark -X lua_script:flowstate.lua -d udp.port==6677,vxlan

flowstate_proto = Proto("midonet_flowstate", "MidoNet SBE FlowState")

inet_addr_types = {
    [1] = "IPv4",
    [2] = "IPv6",
}

-- XXX can we use wireshark's table?
protocols = {
    [1] = "ICMP",
    [6] = "TCP",
    [17] = "UDP",
    [56] = "ICMPv6",
}

nat_types = {
    [1] = "FWD_DNAT",
    [2] = "FWD_STICKY_DNAT",
    [3] = "REV_DNAT",
    [4] = "REV_STICKY_DNAT",
    [5] = "FWD_SNAT",
    [6] = "REV_SNAT",
}

pf_header = ProtoField.none("midonet_flowstate.header", "Message Header")
pf_header_block_length = ProtoField.uint16("midonet_flowstate.header.block_length", "Block Length")
pf_header_template_id = ProtoField.uint16("midonet_flowstate.header.template_id", "Template ID")
pf_header_schema_id = ProtoField.uint16("midonet_flowstate.header.schema_id", "Schema ID")
pf_header_version = ProtoField.uint16("midonet_flowstate.header.version", "Version")

pf_sender = ProtoField.string("midonet_flowstate.sender", "Sender ID")

pf_conntrack = ProtoField.none("midonet_flowstate.contrack", "Conntrack")
pf_conntrack_device = ProtoField.string("midonet_flowstate.conntrack.device", "Device ID")
pf_conntrack_src_ip = ProtoField.string("midonet_flowstate.conntrack.src_ip", "Source IP")
pf_conntrack_dst_ip = ProtoField.string("midonet_flowstate.conntrack.dst_ip", "Destination IP")
pf_conntrack_src_port = ProtoField.uint16("midonet_flowstate.conntrack.src_port", "Source Port")
pf_conntrack_dst_port = ProtoField.uint16("midonet_flowstate.conntrack.dst_port", "Destination Port")
pf_conntrack_protocol = ProtoField.uint8("midonet_flowstate.conntrack.protocol", "Protocol", base.DEC, protocols)
pf_conntrack_src_ip_type = ProtoField.uint8("midonet_flowstate.conntrack.src_ip_type", "Source IP Type", base.DEC, inet_addr_types)
pf_conntrack_dst_ip_type = ProtoField.uint8("midonet_flowstate.conntrack.dst_ip_type", "Destination IP Type", base.DEC, inet_addr_types)

pf_nat = ProtoField.none("midonet_flowstate.nat", "NAT")
pf_nat_device = ProtoField.string("midonet_flowstate.nat.device", "Device ID")
pf_nat_src_ip = ProtoField.string("midonet_flowstate.nat.src_ip", "Source IP")
pf_nat_dst_ip = ProtoField.string("midonet_flowstate.nat.dst_ip", "Destination IP")
pf_nat_value_ip = ProtoField.string("midonet_flowstate.nat.value_ip", "Value IP")
pf_nat_src_port = ProtoField.uint16("midonet_flowstate.nat.src_port", "Source Port")
pf_nat_dst_port = ProtoField.uint16("midonet_flowstate.nat.dst_port", "Destination Port")
pf_nat_value_port = ProtoField.uint16("midonet_flowstate.nat.value_port", "Value Port")
pf_nat_protocol = ProtoField.uint8("midonet_flowstate.nat.protocol", "Protocol", base.DEC, protocols)
pf_nat_src_ip_type = ProtoField.uint8("midonet_flowstate.nat.src_ip_type", "Source IP Type", base.DEC, inet_addr_types)
pf_nat_dst_ip_type = ProtoField.uint8("midonet_flowstate.nat.dst_ip_type", "Destination IP Type", base.DEC, inet_addr_types)
pf_nat_value_ip_type = ProtoField.uint8("midonet_flowstate.nat.value_ip_type", "Value IP Type", base.DEC, inet_addr_types)
pf_nat_nat_type = ProtoField.uint8("midonet_flowstate.nat.nat_type", "NAT Type", base.DEC, nat_types)

pf_trace = ProtoField.none("midonet_flowstate.trace", "Trace")
pf_trace_src_ip = ProtoField.string("midonet_flowstate.trace.src_ip", "Source IP")
pf_trace_dst_ip = ProtoField.string("midonet_flowstate.trace.dst_ip", "Destination IP")
pf_trace_src_mac = ProtoField.ether("midonet_flowstate.trace.src_mac", "Source MAC")
pf_trace_dst_mac = ProtoField.ether("midonet_flowstate.trace.dst_mac", "Destination MAC")
pf_trace_src_port = ProtoField.uint16("midonet_flowstate.trace.src_port", "Source Port")
pf_trace_dst_port = ProtoField.uint16("midonet_flowstate.trace.dst_port", "Destination Port")
pf_trace_ether_type = ProtoField.uint16("midonet_flowstate.trace.ether_type", "Ether Type")
pf_trace_protocol = ProtoField.uint8("midonet_flowstate.trace.protocol", "Protocol", base.DEC, protocols)
pf_trace_src_ip_type = ProtoField.uint8("midonet_flowstate.trace.src_ip_type", "Source IP Type", base.DEC, inet_addr_types)
pf_trace_dst_ip_type = ProtoField.uint8("midonet_flowstate.trace.dst_ip_type", "Destination IP Type", base.DEC, inet_addr_types)

pf_trace_request_ids = ProtoField.none("midonet_flowstate.trace_request_ids", "Trace Request ID")
pf_trace_request_ids_trace = ProtoField.string("midonet_flowstate.trace_request_ids.trace", "Trace Request ID")

pf_port_ids = ProtoField.none("midonet_flowstate.port_ids", "Port ID")
pf_port_ids_port = ProtoField.string("midonet_flowstate.port_ids.port", "Port ID")

pf_egress_port_ids = ProtoField.none("midonet_flowstate.egress_port_ids", "Egress Port ID")
pf_egress_port_ids_port = ProtoField.string("midonet_flowstate.egress_port_ids.port", "Port ID")

flowstate_proto.fields = {
    pf_header,
    pf_header_block_length,
    pf_header_template_id,
    pf_header_schema_id,
    pf_header_version,
    pf_sender,
    pf_conntrack,
    pf_conntrack_device,
    pf_conntrack_src_ip,
    pf_conntrack_dst_ip,
    pf_conntrack_src_port,
    pf_conntrack_dst_port,
    pf_conntrack_protocol,
    pf_conntrack_src_ip_type,
    pf_conntrack_dst_ip_type,
    pf_nat,
    pf_nat_device,
    pf_nat_src_ip,
    pf_nat_dst_ip,
    pf_nat_value_ip,
    pf_nat_src_port,
    pf_nat_dst_port,
    pf_nat_value_port,
    pf_nat_protocol,
    pf_nat_src_ip_type,
    pf_nat_dst_ip_type,
    pf_nat_value_ip_type,
    pf_nat_nat_type,
    pf_trace,
    pf_trace_src_ip,
    pf_trace_dst_ip,
    pf_trace_src_mac,
    pf_trace_dst_mac,
    pf_trace_src_port,
    pf_trace_dst_port,
    pf_trace_ether_type,
    pf_trace_protocol,
    pf_trace_src_ip_type,
    pf_trace_dst_ip_type,
    pf_trace_request_ids,
    pf_trace_request_ids_trace,
    pf_port_ids,
    pf_port_ids_port,
    pf_egress_port_ids,
    pf_egress_port_ids_port}

function uuid(buf)
    -- non-standard encoding of uuid used by midonet sbe
    local a = buf:range(4, 4):le_uint()
    local b = buf:range(2, 2):le_uint()
    local c = buf:range(0, 2):le_uint()
    local d = buf:range(14, 2):le_uint()
    local e1 = buf:range(8, 3):le_uint()
    local e2 = buf:range(8+3, 3):le_uint()
    return string.format("%08x-%04x-%04x-%04x-%06x%06x",a,b,c,d,e2,e1)
end

function parse_group(buf, pf_group, parse_one, tree)
    local block_length = buf(0, 2):le_uint()
    local num_in_group = buf(2, 1):le_uint()
    local total = block_length * num_in_group
    local buf = chop(buf, 3)  -- skip dimension

    for i = 1, num_in_group do
        local block = buf(0, block_length)
        local subtree = tree:add(pf_group, block)
        parse_one(block, subtree)
        buf = chop(buf, block_length)
    end
    return buf
end

function chop(buf, len)
    _, rest = take(buf, len)
    return rest
end

function take(buf, len)
    if buf:len() > len then
        return buf(0, len), buf(len)
    else
        return buf(0, len), nil
    end
end

function flowstate_proto.dissector(buf,pinfo,tree)
    pinfo.cols.protocol:set("MidoNet SBE FlowState")
    local buf = buf
    local subtree = tree:add(flowstate_proto, buf(), "MidoNet SBE FlowState")

    local header, buf = take(buf, 8)
    local header_tree = subtree:add(pf_header, header)
    parse_header(header, header_tree)

    local sender, buf = take(buf, 16)
    subtree:add(pf_sender, sender, uuid(sender))

    buf = parse_group(buf, pf_conntrack, conntrack, subtree)
    buf = parse_group(buf, pf_nat, nat, subtree)
    buf = parse_group(buf, pf_trace, trace, subtree)
    buf = parse_group(buf, pf_trace_request_ids, trace_request_id, subtree)
    buf = parse_group(buf, pf_port_ids, port_id, subtree)
    buf = parse_group(buf, pf_egress_port_ids, port_id, subtree)
end

function parse_header(buf, tree)
    local block_length, buf = take(buf, 2)
    local template_id, buf = take(buf, 2)
    local schema_id, buf = take(buf, 2)
    local version, buf = take(buf, 2)
    tree:add_le(pf_header_block_length, block_length)
    tree:add_le(pf_header_template_id, template_id)
    tree:add_le(pf_header_schema_id, schema_id)
    tree:add_le(pf_header_version, version)
end

function ip(ip, type)
    if type == 1 then
        return tostring(ip:range(0,4):le_ipv4())
    end
    return ip .. " (unknown type " .. type .. ")"
end

function conntrack(buf, tree)
    local buf = buf
    local device, buf = take(buf, 16)
    local src_ip, buf = take(buf, 16)
    local dst_ip, buf = take(buf, 16)
    local src_port, buf = take(buf, 2)
    local dst_port, buf = take(buf, 2)
    local protocol, buf = take(buf, 1)
    local src_ip_type, buf = take(buf, 1)
    local dst_ip_type, buf = take(buf, 1)
    local src_ip_text = ip(src_ip, src_ip_type:le_uint())
    local dst_ip_text = ip(dst_ip, dst_ip_type:le_uint())
    tree:add(pf_conntrack_device, device, uuid(device))
    tree:add(pf_conntrack_src_ip, src_ip, src_ip_text)
    tree:add(pf_conntrack_dst_ip, dst_ip, dst_ip_text)
    tree:add_le(pf_conntrack_src_port, src_port)
    tree:add_le(pf_conntrack_dst_port, dst_port)
    tree:add_le(pf_conntrack_protocol, protocol)
    tree:add_le(pf_conntrack_src_ip_type, src_ip_type)
    tree:add_le(pf_conntrack_dst_ip_type, dst_ip_type)
    tree:append_text(", "..get(protocols, protocol:le_uint()))
    tree:append_text(", Src: "..src_ip_text..":"..src_port:le_uint())
    tree:append_text(", Dst: "..dst_ip_text..":"..dst_port:le_uint())
end

function nat(buf, tree)
    local buf = buf
    local device, buf = take(buf, 16)
    local src_ip, buf = take(buf, 16)
    local dst_ip, buf = take(buf, 16)
    local value_ip, buf = take(buf, 16)
    local src_port, buf = take(buf, 2)
    local dst_port, buf = take(buf, 2)
    local value_port, buf = take(buf, 2)
    local protocol, buf = take(buf, 1)
    local src_ip_type, buf = take(buf, 1)
    local dst_ip_type, buf = take(buf, 1)
    local value_ip_type, buf = take(buf, 1)
    local nat_type, buf = take(buf, 1)
    local src_ip_text = ip(src_ip, src_ip_type:le_uint())
    local dst_ip_text = ip(dst_ip, dst_ip_type:le_uint())
    local value_ip_text = ip(value_ip, value_ip_type:le_uint())
    tree:add(pf_nat_device, device, uuid(device))
    tree:add(pf_nat_src_ip, src_ip, src_ip_text)
    tree:add(pf_nat_dst_ip, dst_ip, dst_ip_text)
    tree:add(pf_nat_value_ip, value_ip, value_ip_text)
    tree:add_le(pf_nat_src_port, src_port)
    tree:add_le(pf_nat_dst_port, dst_port)
    tree:add_le(pf_nat_value_port, value_port)
    tree:add_le(pf_nat_protocol, protocol)
    tree:add_le(pf_nat_src_ip_type, src_ip_type)
    tree:add_le(pf_nat_dst_ip_type, dst_ip_type)
    tree:add_le(pf_nat_value_ip_type, value_ip_type)
    tree:add_le(pf_nat_nat_type, nat_type)
    tree:append_text(", "..get(nat_types, nat_type:le_uint()))
    tree:append_text(", "..get(protocols, protocol:le_uint()))
    tree:append_text(", Src: "..src_ip_text..":"..src_port:le_uint())
    tree:append_text(", Dst: "..dst_ip_text..":"..dst_port:le_uint())
    tree:append_text(", Val: "..value_ip_text..":"..value_port:le_uint())
end

function trace(buf, tree)
    local buf = buf
    local src_ip, buf = take(buf, 16)
    local dst_ip, buf = take(buf, 16)
    local src_mac, buf = take(buf, 6)
    local dst_mac, buf = take(buf, 6)
    local src_port, buf = take(buf, 2)
    local dst_port, buf = take(buf, 2)
    local ether_type, buf = take(buf, 2)
    local protocol, buf = take(buf, 1)
    local src_ip_type, buf = take(buf, 1)
    local dst_ip_type, buf = take(buf, 1)
    tree:add(pf_trace_src_ip, src_ip, ip(src_ip, src_ip_type:le_uint()))
    tree:add(pf_trace_dst_ip, dst_ip, ip(dst_ip, dst_ip_type:le_uint()))
    tree:add(pf_trace_src_mac, src_mac)
    tree:add(pf_trace_dst_mac, dst_mac)
    tree:add_le(pf_trace_src_port, src_port)
    tree:add_le(pf_trace_dst_port, dst_port)
    tree:add_le(pf_trace_ether_type, ether_type)
    tree:add_le(pf_trace_protocol, protocol)
    tree:add_le(pf_trace_src_ip_type, src_ip_type)
    tree:add_le(pf_trace_dst_ip_type, dst_ip_type)
end

function trace_request_id(buf, tree)
    local id = take(buf, 16)
    tree:add(pf_trace_request_ids_trace, id, uuid(id))
    tree:append_text(", "..uuid(id))
end

function port_id(buf, tree)
    local id = take(buf, 16)
    tree:add(pf_port_ids_port, id, uuid(id))
    tree:append_text(", "..uuid(id))
end

function egress_port_id(buf, tree)
    local id = take(buf, 16)
    tree:add(pf_egress_port_ids_port, id, uuid(id))
    tree:append_text(", "..uuid(id))
end

function get(table, value)
    return table[value] or "Unknown"
end

udp_table = DissectorTable.get("udp.port")
udp_table:add(2925, flowstate_proto)
