package com.midokura.mmdpctl.results;

import com.midokura.sdn.dp.Datapath;

public class GetDatapathResult implements Result {

    Datapath datapath;

    public GetDatapathResult(Datapath datapath) {
        this.datapath = datapath;
    }

    @Override
    public void printResult() {
        System.out.println(datapath);
    }
}
