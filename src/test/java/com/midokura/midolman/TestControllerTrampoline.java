package com.midokura.midolman;

import org.junit.Assert;
import org.junit.Test;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalConfiguration.Node;;


public class TestControllerTrampoline {

    @Test
    public void test() {
        Node rootNode = new Node("root");
        Node confNode = new Node("openvswitch");
        rootNode.addChild(confNode);
        confNode = new Node("vrn");
        rootNode.addChild(confNode);
        confNode = new Node("openflow");
        rootNode.addChild(confNode);
        confNode = new Node("memcache");
        rootNode.addChild(confNode);
        confNode = new Node("bridge");
        rootNode.addChild(confNode);
    }

}
