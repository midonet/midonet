package com.midokura.midonet.smoketest.vm.libvirt;

import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import org.apache.commons.io.IOUtils;
import org.glassfish.grizzly.streams.Input;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.midokura.midonet.smoketest.vm.libvirt.LibvirtUtils.uriForHypervisorType;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/15/11
 * Time: 5:32 PM
 */
public class LibvirtHandler {

    private String connectUri;
    private String templateName;
    private String templateImage;
    private HypervisorType hypervisorType;

    private LibvirtHandler(HypervisorType hypervisorType) {
        this(hypervisorType, uriForHypervisorType(hypervisorType));
    }

    private LibvirtHandler(HypervisorType hypervisorType, String uri) {
        this.hypervisorType = hypervisorType;
        this.connectUri = uri;
    }

    public static LibvirtHandler forHypervisor(HypervisorType hypervisorType) {
        return new LibvirtHandler(hypervisorType, uriForHypervisorType(hypervisorType));
    }

    public void setTemplate(String template) {
        this.templateName = template;
    }

    public void setTemplateImage(String templateImage) {
        this.templateImage = templateImage;
    }

    public DomainBuilder newDomain() {
        return new DomainBuilder() {

            String domainName;
            String networkDevice;
            
            @Override
            public DomainBuilder setName(String name) {
                this.domainName = name;
                return this;
            }

            @Override
            public DomainBuilder setNetworkDeviceName(String networkDeviceName) {
                this.networkDevice = networkDeviceName;
                return this;
            }

            @Override
            public VMController build() {
                String overlayImage = buildOverlayImage(domainName, templateImage);
                String domainDescription = updateTemplate(domainName, templateImage, networkDevice);

                try {
                    Connect connect = new Connect(connectUri);
                    Domain domain = connect.domainDefineXML(domainDescription);

                    return new DomainController(hypervisorType, domain.getName());
                } catch (LibvirtException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
                return null;
            }
        };
    }

    private String buildOverlayImage(String domainName, String templateImage) {

        try {
            Process process =
                    new ProcessBuilder()
                        .directory(new File("work"))
                        .command("/bin/bash", "-c", "sudo /home/mtoader/ubuntu-kvm/create_overlay.sh " + templateImage + " " + domainName)
                        .redirectErrorStream(true)
                        .start();

            final InputStream inputStream = process.getInputStream();
            new Thread(new Runnable() {                
                @Override
                public void run() {
                    byte[] buffer = new byte[10];
                    
                    int read = -1;
                    try {
                        while ( (read = inputStream.read(buffer, 0, buffer.length)) != -1 ) {
                            System.out.write(buffer, 0, read);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }
            }).start();

            process.waitFor();

        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        } catch (InterruptedException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
        return templateImage;
    }

    private String updateTemplate(String domainName, String fileLocation, String networkDevice) {
        try {
            String templateContent = IOUtils.toString(this.getClass().getResource("templates/" + this.templateName + ".xml"));

            templateContent = templateContent.replaceAll("\\$\\{domainName\\}", domainName);
            templateContent = templateContent.replaceAll("\\$\\{imageFile\\}", fileLocation);
            templateContent = templateContent.replaceAll("\\$\\{networkDevice\\}", networkDevice);

            return templateContent;
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            return "";
        }
    }

    public interface DomainBuilder {
        public DomainBuilder setName(String name);
        public DomainBuilder setNetworkDeviceName(String tap);
        public VMController build();
    }   
}
