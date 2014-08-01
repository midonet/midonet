CREATE KEYSPACE MidonetFlowState WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1};
USE MidonetFlowState;

CREATE TABLE conntrack_by_ingress_port (
        port uuid,
        proto int,
        srcIp inet,
        srcPort int,
        dstIp inet,
        dstPort int,
        device uuid,
    PRIMARY KEY ((port, proto, srcIp, srcPort, dstIp, dstPort, device))
);
CREATE INDEX ON conntrack_by_ingress_port (port);

CREATE TABLE conntrack_by_egress_port (
        port uuid,
        proto int,
        srcIp inet,
        srcPort int,
        dstIp inet,
        dstPort int,
        device uuid,
    PRIMARY KEY ((port, proto, srcIp, srcPort, dstIp, dstPort, device))
);
CREATE INDEX ON conntrack_by_egress_port (port);

CREATE TABLE nat_by_ingress_port (
        port uuid,
        type text,
        proto int,
        srcIp inet,
        srcPort int,
        dstIp inet,
        dstPort int,
        device uuid,
        translateIp inet,
        translatePort int,
    PRIMARY KEY ((port, type, proto, srcIp, srcPort, dstIp, dstPort, device))
);
CREATE INDEX ON nat_by_ingress_port (port);

CREATE TABLE nat_by_egress_port (
        port uuid,
        type text,
        proto int,
        srcIp inet,
        srcPort int,
        dstIp inet,
        dstPort int,
        device uuid,
        translateIp inet,
        translatePort int,
    PRIMARY KEY ((port, type, proto, srcIp, srcPort, dstIp, dstPort, device))
);
CREATE INDEX ON nat_by_egress_port (port);
