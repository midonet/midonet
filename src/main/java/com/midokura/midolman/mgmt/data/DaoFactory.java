/*
 * @(#)DaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.data.dao.AdRouteDao;
import com.midokura.midolman.mgmt.data.dao.AdminDao;
import com.midokura.midolman.mgmt.data.dao.BgpDao;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.data.dao.RouteDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dao.TenantDao;
import com.midokura.midolman.mgmt.data.dao.VifDao;
import com.midokura.midolman.mgmt.data.dao.VpnDao;

public interface DaoFactory {

    AdminDao getAdminDao();

    AdRouteDao getAdRouteDao();

    BgpDao getBgpDao();

    BridgeDao getBridgeDao();

    ChainDao getChainDao();

    PortDao getPortDao();

    RouteDao getRouteDao();

    RouterDao getRouterDao();

    RuleDao getRuleDao();

    TenantDao getTenantDao();

    VifDao getVifDao();

    VpnDao getVpnDao();

}
