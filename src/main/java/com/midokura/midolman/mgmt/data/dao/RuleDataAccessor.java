/*
 * @(#)RuleDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.mgmt.util.Net;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.ForwardNatRule;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseNatRule;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for rules.
 *
 * @version        1.6 08 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RuleDataAccessor extends DataAccessor {

    /**
     * Constructor
     * @param zkConn Zookeeper connection string
     */
    public RuleDataAccessor(String zkConn) {
        super(zkConn);
    }
    
    private RuleZkManager getRuleZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn);        
        return new RuleZkManager(conn.getZooKeeper(), "/midolman");
    }
    
    private static Set<NatTarget> makeNatTargets(String[] natTargets) {
        Set<NatTarget> targets = new HashSet<NatTarget>(natTargets.length);
        for (String natTarget : natTargets) {
            String[] elems = natTarget.split(",");
            NatTarget t = new NatTarget(
                    Net.convertAddressToInt(elems[0]),
                    Net.convertAddressToInt(elems[1]),
                    (short)Integer.parseInt(elems[2]),
                    (short)Integer.parseInt(elems[3]));
            targets.add(t);
        }
        return targets;
    }
    
    private static String[] makeNatTargetStrings(Set<NatTarget> natTargets) {
        List<String> targets = new ArrayList<String>(natTargets.size());
        for (NatTarget t : natTargets) {
            targets.add(Net.convertAddressToString(t.nwStart) + "," 
                    + Net.convertAddressToString(t.nwEnd) +  "," 
                    + t.tpStart + "," + t.tpEnd);
        }
        return targets.toArray(new String[targets.size()]);
    }
    
    private static Action convertToAction(String type) {
        // ACCEPT, CONTINUE, DROP, JUMP, REJECT, RETURN
        if (type.equals(Rule.Accept)) {
            return Action.ACCEPT;
        } else if (type.equals(Rule.Continue)) {
            return Action.CONTINUE;
        } else if (type.equals(Rule.Drop)) {
            return Action.DROP;
        } else if (type.equals(Rule.Jump)) {
            return Action.JUMP;
        } else if (type.equals(Rule.Reject)) {
            return Action.REJECT;
        } else if (type.equals(Rule.Return)) {
            return Action.RETURN;
        } else {
            throw new IllegalArgumentException("Invalid type passed in.");
        }
    }
    
    private static String convertFromAction(Action a) {
        switch(a) {
            case ACCEPT:
                return Rule.Accept;
            case CONTINUE:
                return Rule.Continue;
            case DROP:
                return Rule.Drop;
            case JUMP:
                return Rule.Jump;
            case REJECT:
                return Rule.Reject;
            case RETURN:
                return Rule.Return;
            default:
                throw new IllegalArgumentException(
                        "Invalid action passed in.");
        }
    }
    
    private static Condition makeCondition(Rule rule) {
        Condition c = new Condition();
        c.conjunctionInv = rule.isCondInvert();
        c.inPortIds = new HashSet<UUID>(Arrays.asList(rule.getInPorts()));
        c.inPortInv = rule.isInvInPorts();
        c.nwDstInv = rule.isInvNwDst();
        c.nwDstIp = Net.convertAddressToInt(rule.getNwDstAddress());
        c.nwDstLength = (byte) rule.getNwDstLength();
        c.nwProto = (byte) rule.getNwProto();
        c.nwProtoInv = rule.isInvNwProto();
        c.nwSrcInv = rule.isInvNwSrc();
        c.nwSrcIp = Net.convertAddressToInt(rule.getNwSrcAddress());
        c.nwSrcLength = (byte) rule.getNwSrcLength();
        c.nwTos = (byte) rule.getNwTos();
        c.nwTosInv = rule.isInvNwTos();
        c.outPortIds = new HashSet<UUID>(Arrays.asList(rule.getOutPorts()));
        c.outPortInv = rule.isInvOutPorts();
        c.tpDstEnd = rule.getTpDstEnd();
        c.tpDstInv = rule.isInvTpDst();
        c.tpDstStart = rule.getTpDstStart();
        c.tpSrcEnd = rule.getTpSrcEnd();
        c.tpSrcInv = rule.isInvTpSrc();
        c.tpSrcStart = rule.getTpSrcStart();
        return c;
    }
    
    private static void setFromCondition(Rule rule, Condition c) {
        rule.setCondInvert(c.conjunctionInv);
        rule.setInPorts(
                c.inPortIds.toArray(new UUID[c.inPortIds.size()]));
        rule.setInvInPorts(c.inPortInv);
        rule.setInvNwDst(c.nwDstInv);
        rule.setInvNwProto(c.nwProtoInv);
        rule.setInvNwSrc(c.nwSrcInv);
        rule.setInvNwTos(c.nwTosInv);
        rule.setInvOutPorts(c.outPortInv);
        rule.setInvTpDst(c.tpDstInv);
        rule.setInvTpSrc(c.tpSrcInv);
        rule.setNwDstAddress(
                Net.convertAddressToString(c.nwDstIp));
        rule.setNwDstLength(c.nwDstLength);
        rule.setNwProto(c.nwProto);
        rule.setNwSrcAddress(
                Net.convertAddressToString(c.nwSrcIp));
        rule.setNwSrcLength(c.nwSrcLength);
        rule.setNwTos(c.nwTos);
        rule.setOutPorts(
                c.outPortIds.toArray(new UUID[c.outPortIds.size()]));
        rule.setTpDstEnd(c.tpDstEnd);
        rule.setTpDstStart(c.tpDstStart);
        rule.setTpSrcEnd(c.tpSrcEnd);
        rule.setTpSrcStart(c.tpSrcStart);
    }
    
    private static com.midokura.midolman.rules.Rule 
            convertToZkRule(Rule rule) {
        Condition cond = makeCondition(rule);
        String type = rule.getType();
        Action action = convertToAction(type);
        com.midokura.midolman.rules.Rule r = null;
        if (Arrays.asList(Rule.SimpleRuleTypes).contains(type)) {
            r = new LiteralRule(cond, action);
        } else if (Arrays.asList(Rule.NatRuleTypes).contains(type)) {
            Set<NatTarget> targets = makeNatTargets(rule.getNatTargets());
            r = new ForwardNatRule(cond, targets, 
                    convertToAction(rule.getFlowAction()),
                    type.equals(Rule.DNAT));
        } else if (Arrays.asList(Rule.RevNatRuleTypes).contains(type)) {
            r = new ReverseNatRule(cond, 
                    convertToAction(rule.getFlowAction()),
                    type.equals(Rule.DNAT));
        } else {
            // Jump
            r = new JumpRule(cond, rule.getJumpChainName());
        }
        return r;
    }
    
    private static Rule convertToRule(
            com.midokura.midolman.rules.Rule zkRule) {
        Rule rule = new Rule();
        rule.setChainId(zkRule.chainId);
        setFromCondition(rule, zkRule.getCondition());
        if (zkRule instanceof LiteralRule) {
            rule.setType(convertFromAction(zkRule.action));
        } else if (zkRule instanceof ForwardNatRule) {
            String[] targets = makeNatTargetStrings( 
                    ((ForwardNatRule) zkRule).getNatTargets());
            rule.setNatTargets(targets);
            rule.setFlowAction(convertFromAction(zkRule.action));
            if (((NatRule) zkRule).dnat) {
                rule.setType(Rule.DNAT);
            } else {
                rule.setType(Rule.SNAT);
            } 
        } else if (zkRule instanceof ReverseNatRule) {
            if (((NatRule) zkRule).dnat) {
                rule.setType(Rule.DNAT);
            } else {
                rule.setType(Rule.SNAT);
            }          
            rule.setFlowAction(convertFromAction(zkRule.action));
        } else {
            // TODO: how about JumpToChain UUID??
            rule.setJumpChainName(((JumpRule) zkRule).jumpToChain);
        }
        return rule;
    }   
    
    /**
     * Add rule object to Zookeeper directories.
     * 
     * @param   rule  Rule object to add.
     * @throws  Exception  Error adding data to Zookeeper.
     */
    public void create(Rule rule) throws Exception {
        com.midokura.midolman.rules.Rule zkRule = 
            convertToZkRule(rule);
        RuleZkManager manager = getRuleZkManager();
        manager.create(rule.getId(), zkRule);
    }

    public void delete(UUID id) throws Exception {
        RuleZkManager manager = getRuleZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }
    
    /**
     * Update Port entry in ZooKeeper.
     * 
     * @param   port  Port object to update.
     * @throws  Exception  Error adding data to ZooKeeper.
     */
    public void update(UUID id, Rule rule) throws Exception {
        com.midokura.midolman.rules.Rule zkRule = convertToZkRule(rule);
        RuleZkManager manager = getRuleZkManager();
        manager.update(id, zkRule);
    }
    
    /**
     * Get a Rule for the given ID.
     * 
     * @param   id  Rule ID to search.
     * @return  Rule object with the given ID.
     * @throws  Exception  Error getting data to Zookeeper.
     */
    public Rule get(UUID id) throws Exception {
        RuleZkManager manager = getRuleZkManager();
        com.midokura.midolman.rules.Rule zkRule = 
            manager.get(id);
        // TODO: Throw NotFound exception here.
        Rule rule = convertToRule(zkRule);
        rule.setId(id);
        return rule;
    }
    
    /**
     * Get a list of rules for a chain.
     * 
     * @param chainId  UUID of chain.
     * @return  A Set of Rules
     * @throws Exception  Zookeeper(or any) error.
     */
    public Rule[] list(UUID chainId) throws Exception {
        RuleZkManager manager = getRuleZkManager();
        List<Rule> rules = new ArrayList<Rule>();
        HashMap<UUID, com.midokura.midolman.rules.Rule> configs = 
            manager.list(chainId);
        for (Map.Entry<UUID, com.midokura.midolman.rules.Rule> entry : 
            configs.entrySet()) {
            Rule rule = convertToRule(entry.getValue());
            rule.setId(entry.getKey());
            rules.add(rule);            
        }
        return rules.toArray(new Rule[rules.size()]);
    }
    
}
