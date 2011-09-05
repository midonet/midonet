package com.midokura.midolman.layer4;

import java.util.Set;

import com.midokura.midolman.rules.NatTarget;

public class NatLeaseManager implements NatMapping {

    @Override
    public NwTpPair allocateDnat(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst, Set<NatTarget> nats) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NwTpPair lookupDnatFwd(int nwSrc, short tpSrc, int oldNwDst,
            short oldTpDst) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NwTpPair lookupDnatRev(int nwSrc, short tpSrc, int newNwDst,
            short newTpDst) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NwTpPair allocateSnat(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst, Set<NatTarget> nats) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NwTpPair lookupSnatFwd(int oldNwSrc, short oldTpSrc, int nwDst,
            short tpDst) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public NwTpPair lookupSnatRev(int newNwSrc, short newTpSrc, int nwDst,
            short tpDst) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void updateSnatTargets(Set<NatTarget> targets) {
        // TODO Auto-generated method stub
        
    }

}
