package com.midokura.midonet.smoketest.vm;

import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

/**
 * Author: Toader Mihai Claudiu <mtoader@midokura.com>
 * <p/>
 * Date: 11/10/11
 * Time: 10:06 AM
 */
public class LibvirtVMController implements VMController {


    VMType vmType;
    String vmName;

    public LibvirtVMController(VMType vmType, String vmName) {
        this.vmType = vmType;
        this.vmName = vmName;

        validateDomain();
    }

    private boolean validateDomain() {
        Boolean isDomainValid = executeWithDomain(new DomainAwareExecutor<Boolean>() {
            @Override
            public Boolean execute(Domain domain) throws LibvirtException {
                return domain.getInfo() != null;
            }
        });

        return isDomainValid != null && isDomainValid;
    }

    private String libvirtUriForType(VMType vmType) {
        switch (vmType) {
            case Kvm:
            case Qemu:
                return "qemu:///system";
            case Xen:
                return "xen:///";
            case VBox:
                return "vbox:///session";
        }

        return "";
    }

    public void startup() {
        executeWithDomain(new DomainAwareExecutor<Integer>() {
            @Override
            public Integer execute(Domain domain) throws LibvirtException {
                return domain.create();
            }
        });
    }

    public void shutdown() {
        executeWithDomain(new DomainAwareExecutor<Void>() {
            @Override
            public Void execute(Domain domain) throws LibvirtException {
                domain.destroy();
                return null;
            }
        });
    }

    private <T> T executeWithDomain(DomainAwareExecutor<T> callback) {
        return executeWithDomain(callback, false);
    }

    private <T> T executeWithDomain(DomainAwareExecutor<T> callback, boolean readOnly) {
        try {

            Connect connection = new Connect(libvirtUriForType(vmType), readOnly);

            Domain domain = connection.domainLookupByName(vmName);
            if ( domain != null ) {
                return callback.execute(domain);
            }
        } catch (LibvirtException ex) {
            ex.printStackTrace();
        }

        return null;
    }

    public interface DomainAwareExecutor<T> {

        public T execute(Domain domain) throws LibvirtException;

    }
}
