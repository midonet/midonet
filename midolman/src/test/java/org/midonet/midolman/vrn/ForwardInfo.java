/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.vrn;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import akka.actor.ActorSystem$;
import scala.Option;
import scala.util.Left;
import scala.util.Right;

import org.midonet.midolman.simulation.PacketContext;
import org.midonet.packets.Ethernet;
import org.midonet.sdn.flows.FlowTagger;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.util.functors.Callback0;

/* VRNController creates and partially populate an instance of
 * ForwardInfo to call ForwardingElement.process(fInfo).  The
 * ForwardingElement populates a number of fields to indicate various
 * decisions:  the next action for the packet, the egress port,
 * the packet at egress (i.e. after possible modifications).
 */
public class ForwardInfo extends PacketContext {

    // These fields are filled by the caller of ForwardingElement.process():
    public UUID inPortId;
    public Ethernet pktIn;
    public WildcardMatch flowMatch; // (original) match of any eventual flows
    public WildcardMatch matchIn; // the match as it enters the ForwardingElement
    public Set<UUID> _portGroups = new HashSet<UUID>();
    public boolean internallyGenerated = false;
    public Integer generatedPacketCookie;

    public enum Action {
        DROP,
        NOT_IPV4,
        FORWARD,
        CONSUMED,
        PAUSED,
    }

    @Override
    public Option<Object> flowCookie() {
        return Option.apply(null);
    }

    // These fields are filled by ForwardingElement.process():
    public Action action;
    public UUID outPortId;
    public WildcardMatch matchOut; // the match as it exits the ForwardingElement

    // Used by FEs that want notification when the flow is removed.
    private Collection<UUID> notifyFEs = new HashSet<UUID>();
    // Used by the VRNCoordinator to detect loops.
    private Map<UUID, Integer> traversedFEs = new HashMap<UUID, Integer>();
    // Used for coarse invalidation. If any element in this set changes
    // there's a chance the flow is no longer correct. Elements can be
    // Routers, Bridges, Ports and Chains.
    private Set<FlowTagger.FlowTag> flowTags = new HashSet<>();
    public int depth = 0;  // depth in the VRN simulation

    // Used for connection tracking.
    private boolean connectionTracked = false;
    private boolean forwardFlow;
    private UUID ingressFE;

    public ForwardInfo(boolean internallyGenerated, WildcardMatch wcMatch, UUID ingressFE) {
        super(internallyGenerated ? new Right<>(UUID.randomUUID()) : new Left<Object, UUID>(1),
              null, 0L, Option.empty(), wcMatch, ActorSystem$.MODULE$.create());
        flowMatch = wcMatch;
        this.ingressFE = ingressFE;
        this.internallyGenerated = internallyGenerated;
    }

    @Override
    public String toString() {
        return "ForwardInfo [inPortId=" + inPortId +
               ", pktIn=" + pktIn + ", matchIn=" + matchIn +
               ", action=" + action + ", outPortId=" + outPortId +
               ", matchOut=" + matchOut + ", depth=" + depth + "]";
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    @Override
    public Set<UUID> portGroups() {
        return _portGroups;
    }

    public void addTraversedFE(UUID deviceId) {
        Integer numTraversed = traversedFEs.get(deviceId);
        if (null == numTraversed)
            traversedFEs.put(deviceId, 1);
        else
            traversedFEs.put(deviceId, numTraversed+1);
    }

    public int getTimesTraversed(UUID deviceId) {
        Integer numTraversed = traversedFEs.get(deviceId);
        return null == numTraversed ? 0 : numTraversed;
    }

    public int getNumFEsTraversed() {
        return traversedFEs.size();
    }

    public Collection<UUID> getNotifiedFEs() {
        return notifyFEs;
    }

    public void addRemovalNotification(UUID deviceId) {
        notifyFEs.add(deviceId);
    }

    public boolean isGeneratedPacket() {
        return internallyGenerated;
    }

    public UUID inPortId() {
        return inPortId;
    }

    public void setInPortId(UUID inPortId) {
        this.inPortId = inPortId;
    }

    public WildcardMatch getMatchIn() {
        return matchIn;
    }

    public void setMatchIn(WildcardMatch matchIn) {
        this.matchIn = matchIn;
    }

    public WildcardMatch getMatchOut() {
        return matchOut;
    }

    public void setMatchOut(WildcardMatch matchOut) {
        this.matchOut = matchOut;
    }

    @Override
    public UUID outPortId() {
        return outPortId;
    }

    public void setOutPortId(UUID outPortId) {
        this.outPortId = outPortId;
    }

    public Ethernet getPktIn() {
        return pktIn;
    }

    @Override
    public void addFlowTag(FlowTagger.FlowTag tag) {
        flowTags.add(tag);
    }

    @Override
    public void addFlowRemovedCallback(Callback0 cb) {
        // XXX(guillermo) do nothing, this class is unused outside of tests
        // and going away. Right?
    }

    @Override
    public Option<Object> parentCookie() {
        return Option.apply((Object) generatedPacketCookie);
    }
}
