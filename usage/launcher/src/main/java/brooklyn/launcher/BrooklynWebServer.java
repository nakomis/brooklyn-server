package brooklyn.launcher;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jetty.http.ssl.SslContextFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynServiceAttributes;
import brooklyn.location.PortRange;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.location.basic.PortRanges;
import brooklyn.management.ManagementContext;
import brooklyn.util.BrooklynLanguageExtensions;
import brooklyn.util.MutableMap;
import brooklyn.util.ResourceUtils;
import brooklyn.util.flags.FlagUtils;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.flags.TypeCoercions;
import brooklyn.util.text.Strings;
import brooklyn.util.web.ContextHandlerCollectionHotSwappable;

import com.google.common.base.Throwables;

/**
 * Starts the web-app running, connected to the given management context
 */
public class BrooklynWebServer {
    private static final Logger log = LoggerFactory.getLogger(BrooklynWebServer.class);

    public static final String BROOKLYN_WAR_URL = "classpath://brooklyn.war";

    protected Server server;

    @SetFromFlag
    protected PortRange port = PortRanges.fromString("8081+");
    @SetFromFlag
    protected PortRange httpsPort = PortRanges.fromString("8443+");
    protected volatile int actualPort = -1;

    @SetFromFlag
    protected String war = BROOKLYN_WAR_URL;

    /**
     * map of context-prefix to file
     */
    @SetFromFlag
    private Map<String, String> wars = new LinkedHashMap<String, String>();

    @SetFromFlag
    private Map<String, Object> attributes = new LinkedHashMap<String, Object>();

    private ManagementContext managementContext;

    public BrooklynWebServer(ManagementContext managementContext) {
        this(new LinkedHashMap(), managementContext);
    }

    @SetFromFlag(defaultVal = "false")
    private boolean httpsEnabled;

    @SetFromFlag
    private String keystorePath;

    @SetFromFlag
    private String keystorePassword;

    @SetFromFlag
    private String truststorePath;

    @SetFromFlag
    private String trustStorePassword;


    /**
     * accepts flags:  port,
     * war (url of war file which is the root),
     * wars (map of context-prefix to url),
     * attrs (map of attribute-name : object pairs passed to the servlet)
     */
    public BrooklynWebServer(Map flags, ManagementContext managementContext) {
        this.managementContext = managementContext;
        Map leftovers = FlagUtils.setFieldsFromFlags(flags, this);
        if (!leftovers.isEmpty())
            log.warn("Ignoring unknown flags " + leftovers);
    }

    public BrooklynWebServer(ManagementContext managementContext, int port) {
        this(managementContext, port, "brooklyn.war");
    }

    public BrooklynWebServer(ManagementContext managementContext, int port, String warUrl) {
        this(MutableMap.of("port", port, "war", warUrl), managementContext);
    }

    public BrooklynWebServer setPort(Object port) {
        if (getActualPort()>0)
            throw new IllegalStateException("Can't set port after port has been assigned to server (using "+getActualPort()+")");
        this.port = TypeCoercions.coerce(port, PortRange.class);
        return this;
    }

    public PortRange getRequestedPort() {
        return port;
    }
    
    /** returns port where this is running, or -1 if not yet known */
    public int getActualPort() {
        return actualPort;
    }

    /** interface/address where this server binds */
    public InetAddress getAddress() {
        return LocalhostMachineProvisioningLocation.getLocalhostInetAddress();
    }
    
    /** URL for accessing this web server (root context) */
    public String getRootUrl() {
        if (getActualPort()>0){
            String protocol = httpsEnabled?"https":"http";
            return protocol+"://"+getAddress().getHostName()+":"+getActualPort()+"/";
        }else{
            return null;
        }
    }

      /** sets the WAR to use as the root context (only if server not yet started);
     * cf deploy("/", url) */
    public BrooklynWebServer setWar(String url) {
        this.war = url;
        return this;
    }

    /** specifies a WAR to use at a given context path (only if server not yet started);
     * cf deploy(path, url) */
    public BrooklynWebServer addWar(String path, String warUrl) {
        deploy(path, warUrl);
        return this;
    }

    /** @deprecated use setAttribute */
    public BrooklynWebServer addAttribute(String field, Object value) {
        return setAttribute(field, value);
    }
    /** Specifies an attribute passed to deployed webapps 
     * (in addition to {@link BrooklynServiceAttributes#BROOKLYN_MANAGEMENT_CONTEXT} */
    public BrooklynWebServer setAttribute(String field, Object value) {
        attributes.put(field, value);
        return this;
    }
    /** Specifies attributes passed to deployed webapps 
     * (in addition to {@link BrooklynServiceAttributes#BROOKLYN_MANAGEMENT_CONTEXT} */
    public BrooklynWebServer putAttributes(Map newAttrs) {
        attributes.putAll(newAttrs);
        return this;
    }

    ContextHandlerCollectionHotSwappable handlers = new ContextHandlerCollectionHotSwappable();
    
    /**
     * Starts the embedded web application server.
     */
    public synchronized void start() throws Exception {
        if (server!=null) throw new IllegalStateException(""+this+" already running");
        if (actualPort==-1){
            actualPort = LocalhostMachineProvisioningLocation.obtainPort(getAddress(), httpsEnabled?httpsPort:port);
        }

        if(httpsEnabled){
            log.info("Starting secured Brooklyn console at "+getRootUrl()+", running " + war + (wars != null ? " and " + wars.values() : ""));
        } else{
            log.info("Starting unsecured Brooklyn console at " + getRootUrl() + ", running " + war + (wars != null ? " and " + wars.values() : ""));
        }

        server = new Server(actualPort);

        if(httpsEnabled){
            //by default the server is configured with a http connector, this needs to be removed since we are going
            //to provide https
            for(Connector c: server.getConnectors()){
                server.removeConnector(c);
            }

            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStore(checkFileExists(keystorePath, "keystore"));
            sslContextFactory.setKeyStorePassword(keystorePassword);
            if (!Strings.isEmpty(truststorePath)) {
                sslContextFactory.setTrustStore(checkFileExists(truststorePath, "truststore"));
                sslContextFactory.setTrustStorePassword(trustStorePassword);
            }

            SslSocketConnector sslSocketConnector = new SslSocketConnector(sslContextFactory);
            sslSocketConnector.setPort(actualPort);
            server.addConnector(sslSocketConnector);
        }

        addShutdownHook();

        for (Map.Entry<String, String> entry : wars.entrySet()) {
            String pathSpec = entry.getKey();
            String warUrl = entry.getValue();
            deploy(pathSpec, warUrl);
        }

        deploy("/", war);

        server.setHandler(handlers);
        server.start();
        //reinit required because grails wipes our language extension bindings
        BrooklynLanguageExtensions.reinit();

        if(httpsEnabled){
            log.info("Started secured Brooklyn console at "+getRootUrl()+", running " + war + (wars != null ? " and " + wars.values() : ""));
        }else{
            log.info("Started unsecured Brooklyn console at "+getRootUrl()+", running " + war + (wars != null ? " and " + wars.values() : ""));
        }
    }

    private String checkFileExists(String path, String name) {
        if(!new File(path).exists()){
            throw new IllegalArgumentException("Could not find "+name+": "+path);
        }
        return path;
    }

    /**
     * Asks the app server to stop and waits for it to finish up.
     */
    public synchronized void stop() throws Exception {
        if (server==null) return;
        String root = getRootUrl();
        ResourceUtils.removeShutdownHook(shutdownHook);
        log.info("Stopping Brooklyn web console at "+root+ " (" + war + (wars != null ? " and " + wars.values() : "") + ")");

        server.stop();
        try {
            server.join();
        } catch (Exception e) {
            /* NPE may be thrown e.g. if threadpool not started */
        }
        server = null;
        LocalhostMachineProvisioningLocation.releasePort(getAddress(), actualPort);
        actualPort = -1;
        log.info("Stopped Brooklyn web console at "+root);
    }

    /** serve given WAR at the given pathSpec; if not yet started, it is simply remembered until start;
     * if server already running, the context for this WAR is started.
     * @return the context created and added as a handler 
     * (and possibly already started if server is started,
     * so be careful with any changes you make to it!)  */
    public WebAppContext deploy(String pathSpec, String warUrl) {
        String cleanPathSpec = pathSpec;
        while (cleanPathSpec.startsWith("/"))
            cleanPathSpec = cleanPathSpec.substring(1);
        boolean isRoot = cleanPathSpec.isEmpty();

        File tmpWarFile = ResourceUtils.writeToTempFile(new ResourceUtils(this).getResourceFromUrl(warUrl), 
                isRoot ? "ROOT" : ("embedded-" + cleanPathSpec), ".war");

        WebAppContext context = new WebAppContext();
        context.setAttribute(BrooklynServiceAttributes.BROOKLYN_MANAGEMENT_CONTEXT, managementContext);
        for (Map.Entry<String, Object> attributeEntry : attributes.entrySet()) {
            context.setAttribute(attributeEntry.getKey(), attributeEntry.getValue());
        }

        context.setWar(tmpWarFile.getAbsolutePath());
        context.setContextPath("/" + cleanPathSpec);
        context.setParentLoaderPriority(true);

        deploy(context);
        return context;
    }

    Thread shutdownHook = null;
    protected synchronized void addShutdownHook() {
        if (shutdownHook!=null) return;
        // some webapps (eg grails) can generate a lot of output if we don't shut down the browser first 
        shutdownHook = ResourceUtils.addShutdownHook(new Runnable() {
            @Override
            public void run() {
                log.info("BrooklynWebServer detected shut-down: stopping web-console");
                try {
                    stop();
                } catch (Exception e) {
                    log.error("Failure shutting down web-console: "+e, e);
                }
            }
        });
    }

    public void deploy(WebAppContext context) {
        try {
            handlers.updateHandler(context);
        } catch (Exception e) {
            Throwables.propagate(e);
        }
    }
    
    public Server getServer() {
        return server;
    }
}
