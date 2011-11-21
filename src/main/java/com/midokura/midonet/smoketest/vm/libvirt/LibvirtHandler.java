/*
 * Copyright 2011 Midokura Europe SARL
 */
package com.midokura.midonet.smoketest.vm.libvirt;

import com.midokura.midonet.smoketest.vm.HypervisorType;
import com.midokura.midonet.smoketest.vm.VMController;
import com.midokura.tools.process.DrainTargets;
import com.midokura.tools.process.ProcessOutputDrainer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.libvirt.Connect;
import org.libvirt.Domain;
import org.libvirt.LibvirtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URL;
import java.util.Properties;

import static com.midokura.midonet.smoketest.vm.libvirt.LibvirtUtils.uriForHypervisorType;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/15/11
 * Time: 5:32 PM
 */
public class LibvirtHandler {

    private final static Logger log = LoggerFactory.getLogger(LibvirtHandler.class);

    private static final String RUNTIME_ENV_PROPERTIES = "/libvirt_runtime_env.properties";

    private String connectUri;
    private String templateName;
    private String baseImage;
    private HypervisorType hypervisorType;

    private Properties properties;

    private static final String VM_BASE_IMAGE = "libvirt.vm.base.image.file";
    private static final String VM_IMAGES_FOLDER = "libvirt.vm.images.folder";
    private static final String VM_WORK_FOLDER = "libvirt.vm.work.folder";
    private static final String VM_TEMPLATES_FOLDER = "libvirt.templates.folder";

    private LibvirtHandler(HypervisorType hypervisorType) {
        this(hypervisorType, uriForHypervisorType(hypervisorType));
    }

    private LibvirtHandler(HypervisorType hypervisorType, String uri) {
        this.hypervisorType = hypervisorType;
        this.connectUri = uri;

        initializeProperties();
    }

    private void initializeProperties() {
        Properties properties = new Properties();
        try {
            properties.load(getClass().getResourceAsStream(RUNTIME_ENV_PROPERTIES));
            log.info("Loaded properties from classpath resource: " + RUNTIME_ENV_PROPERTIES);
            this.properties = properties;

            setTemplateImage(properties.getProperty(VM_BASE_IMAGE));
        } catch (IOException e) {
            log.warn("Could not read properties from classpath resource: " + RUNTIME_ENV_PROPERTIES, e);
        }
    }

    public static LibvirtHandler forHypervisor(HypervisorType hypervisorType) {
        return new LibvirtHandler(hypervisorType, uriForHypervisorType(hypervisorType));
    }

    public void setTemplate(String template) {
        this.templateName = template;
    }

    public void setTemplateImage(String templateImage) {
        this.baseImage = templateImage;
    }

    public DomainBuilder newDomain() {
        return new DomainBuilder() {

            String domainName;
            String hostName;
            String networkDevice;

            @Override
            public DomainBuilder setDomainName(String name) {
                this.domainName = name;
                if (this.hostName == null) {
                    this.hostName = domainName;
                }
                return this;
            }

            @Override
            public DomainBuilder setHostName(String hostName) {
                this.hostName = hostName;
                return this;
            }

            @Override
            public DomainBuilder setNetworkDevice(String networkDeviceName) {
                this.networkDevice = networkDeviceName;
                return this;
            }

            @Override
            public VMController build() {

                deleteDomainIfExists();

                String overlayImage = buildOverlayImage(hostName, domainName, baseImage);
                String domainDescription = updateTemplate(domainName, overlayImage, networkDevice);

                if (domainDescription == null || domainDescription.trim().length() == 0) {
                    return null;
                }

                try {
                    Connect connect = new Connect(connectUri);
                    Domain domain = connect.domainDefineXML(domainDescription);
                    return new DomainController(hypervisorType, domain, hostName);
                } catch (LibvirtException e) {
                    log.error("Error while defining new domain: " + domainName, e);
                }

                return null;
            }

            private void deleteDomainIfExists() {
                try {
                    Connect connect = new Connect(connectUri);

                    // undefine this domain if it was already defined (this works for domains that aren't running yet).
                    String[] existingDomains = connect.listDefinedDomains();                    
                    for (String existingDomain : existingDomains) {
                        if (existingDomain.equals(domainName)) {
                            Domain domain = connect.domainLookupByName(domainName);
                            domain.undefine();
                            return;
                        }
                    }
                    
                    int[] runningDomainIds = connect.listDomains();
                    for (int runningDomainId : runningDomainIds) {
                        Domain runningDomain = connect.domainLookupByID(runningDomainId);
                        if ( runningDomain != null && runningDomain.getName().equals(domainName) ) {
                            // this is our domain .. so we stop it first (forcibly)
                            runningDomain.destroy();

                            // and then undefine it
                            runningDomain.undefine();
                            return;
                        }
                    }
                } catch (LibvirtException e) {
                    log.error("Error while deleting domain: " + domainName, e);
                }
            }
        };
    }

    private String buildOverlayImage(String hostName, String domainName, String templateImage) {

        File create_overlay_script = new File(properties.getProperty(VM_WORK_FOLDER), "create_domain_overlay.sh");
        File targetOverlayFile = new File(properties.getProperty(VM_IMAGES_FOLDER), domainName + "_image.ovl");

        try {
            create_overlay_script.delete();
            FileUtils.copyURLToFile(
                    getClass().getResource("/com/midokura/midonet/smoketest/vm/libvirt/tools/create_domain_overlay.sh"),
                    create_overlay_script);

            create_overlay_script.setExecutable(true, false);

            Process process =
                    new ProcessBuilder()
                            .directory(new File(properties.getProperty(VM_WORK_FOLDER)))
                            .command("/bin/bash", "-c", create_overlay_script.getAbsolutePath() + " " + templateImage + " " + hostName + " " + targetOverlayFile.getAbsolutePath())
                            .redirectErrorStream(true)
                            .start();

            new ProcessOutputDrainer(process).drainOutput(DrainTargets.slf4jTarget(log, "create_domain_overlay.sh"));

            process.waitFor();

            return targetOverlayFile.getAbsolutePath();
        } catch (IOException e) {
            log.error("Exception while creating overlay image for domain : " + domainName, e);
        } catch (InterruptedException e) {
            log.error("Exception while creating overlay image for domain : " + domainName, e);
        }

        return null;
    }

    private String updateTemplate(String domainName, String fileLocation, String networkDevice) {
        try {
            String templateContent = getTemplateContent();

            templateContent = templateContent.replaceAll("\\$\\{domainName\\}", domainName);
            templateContent = templateContent.replaceAll("\\$\\{imageFile\\}", fileLocation);
            templateContent = templateContent.replaceAll("\\$\\{networkInterface\\}", createNetworkSpecification(networkDevice));

            return templateContent;
        } catch (IOException e) {
            log.error("Error while constructing domain description for domain: " + domainName, e);
            return "";
        }
    }

    private String createNetworkSpecification(String networkDevice) {
        String defaultNetworkConfig = "   <interface type='network'>\n" +
                "      <source network='default'/>\n" +
                "    </interface>";
        
        if (networkDevice == null || networkDevice.trim().length() == 0 ) {
            return defaultNetworkConfig;
        }
        
        String ethernetNetworkConfig = "" +
                "        <interface type='ethernet'>\n" +
                "            <script path=''/>\n" +
                "            <target dev='${networkDevice}'/>\n" +
//                "            <model type='virtio'/>\n" +
//                "            <address type='pci' domain='0x0000' bus='0x00' slot='0x03' function='0x0'/>\n" +
                "        </interface>";


        return ethernetNetworkConfig.replaceAll("\\$\\{networkDevice\\}", networkDevice);
    }

    private String getTemplateContent() throws IOException {

        File templateFile = new File(properties.getProperty(VM_TEMPLATES_FOLDER), this.templateName + ".xml");

        if (templateFile.exists() && templateFile.isFile() && templateFile.canRead()) {
            log.info("Loading template named " + this.templateName + " from: " + templateFile.getAbsolutePath());
            return FileUtils.readFileToString(templateFile);
        }

        URL templateResource = this.getClass().getResource("templates/" + this.templateName + ".xml");
        if (templateResource != null) {
            log.info("Loading template name " + this.templateName + " from classpath resource: " + templateResource.toExternalForm());
            return IOUtils.toString(templateResource);
        }

        throw
                new FileNotFoundException("Could not find template in the configured templates folder: "
                        + templateFile.getParentFile().getAbsolutePath() + " or as a resource in the classpath: " + "templates/" + this.templateName + ".xml");
    }

    public boolean isRuntimeConfigurationValid() {        
        
        File workFolder = new File(properties.getProperty(VM_WORK_FOLDER));  
        if ( ! workFolder.exists() || ! workFolder.isDirectory() ) {
            return false;    
        }
        
        File imagesFolder = new File(properties.getProperty(VM_IMAGES_FOLDER));
        if ( ! imagesFolder.exists() || ! imagesFolder.isDirectory() ) {
            return false;
        }
        
        File templatesFolder = new File(properties.getProperty(VM_TEMPLATES_FOLDER));
        if ( templatesFolder.exists() && ! templatesFolder.isDirectory() ) {
            return false;
        }
        
        File baseImageFile = new File(baseImage);
        if ( ! baseImageFile.exists() || ! baseImageFile.isFile() || ! baseImageFile.canRead() ) {
            return false;
        }

        return true;
    }

    public interface DomainBuilder {
        public DomainBuilder setDomainName(String domainName);

        public DomainBuilder setHostName(String hostName);

        public DomainBuilder setNetworkDevice(String tap);

        public VMController build();
    }
}
