package de.felixhertweck.otproxy.protocol.iec61850;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import de.felixhertweck.otproxy.config.RulesConfig;
import de.felixhertweck.otproxy.core.pipeline.RequestPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MMS server side of the IEC 61850 proxy. Mirrors the upstream IED's model to downstream clients,
 * enforces the read allow-list by pruning the exposed model, intercepts control operations via
 * {@link RuleEnforcingServerListener}, and keeps measured/status values fresh by polling upstream.
 *
 * <p>Analogous to {@code ModbusTcpListener}, but terminating: it speaks MMS on both sides instead
 * of forwarding raw bytes, because IEC 61850 cannot be byte-proxied.
 */
public class Iec61850ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(Iec61850ProxyServer.class);

    private static final long POLL_INTERVAL_MILLIS = 1000;

    private final int bindPort;
    private final RequestPipeline pipeline;
    private final Iec61850Upstream upstream;
    private final RulesConfig rules;

    private ServerSap serverSap;
    private ServerModel exposedModel;
    private ScheduledExecutorService scheduler;

    public Iec61850ProxyServer(
            int bindPort, RequestPipeline pipeline, Iec61850Upstream upstream, RulesConfig rules) {
        this.bindPort = bindPort;
        this.pipeline = pipeline;
        this.upstream = upstream;
        this.rules = rules;
    }

    /** Builds the exposed model, starts the MMS server, and begins polling upstream values. */
    public void start() throws IOException {
        // Filter a private copy so the server's nodes are independent of the client model.
        exposedModel = Iec61850ModelFilter.buildExposedModel(upstream.getModel().copy(), rules);
        Iec61850ControlModelNormalizer.normalize(exposedModel);

        serverSap = new ServerSap(bindPort, 0, null, exposedModel, null);
        serverSap.startListening(new RuleEnforcingServerListener(pipeline, upstream));
        log.info("IEC 61850 proxy listening on port {}", bindPort);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
                this::syncValues,
                POLL_INTERVAL_MILLIS,
                POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    /** Copies fresh status/measurement values from the upstream mirror into the exposed model. */
    private void syncValues() {
        try {
            upstream.refreshValues();
            ServerModel mirror = upstream.getModel().copy();
            List<BasicDataAttribute> updated = new ArrayList<>();
            for (BasicDataAttribute serverBda : exposedModel.getBasicDataAttributes()) {
                Fc fc = serverBda.getFc();
                if (fc != Fc.ST && fc != Fc.MX) continue; // only readable status/measurements
                ModelNode src = mirror.findModelNode(serverBda.getReference(), fc);
                if (src instanceof BasicDataAttribute srcBda) {
                    serverBda.setValueFrom(srcBda);
                    updated.add(serverBda);
                }
            }
            if (!updated.isEmpty()) {
                serverSap.setValues(updated);
            }
        } catch (RuntimeException e) {
            log.warn("Value sync cycle failed: {}", e.getMessage());
        }
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (serverSap != null) {
            serverSap.stop();
        }
        upstream.close();
        log.info("IEC 61850 proxy stopped.");
    }
}
