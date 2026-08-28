package de.felixhertweck.otproxy.integration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientSap;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerEventListener;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import com.beanit.iec61850bean.ServiceError;
import de.felixhertweck.otproxy.config.Iec61850PointRuleConfig;
import de.felixhertweck.otproxy.config.ProxyConfig;
import de.felixhertweck.otproxy.config.RulesConfig;
import de.felixhertweck.otproxy.core.pipeline.RequestPipeline;
import de.felixhertweck.otproxy.core.rules.RuleEngine;
import de.felixhertweck.otproxy.protocol.iec61850.Iec61850ProxyServer;
import de.felixhertweck.otproxy.protocol.iec61850.Iec61850Upstream;
import de.felixhertweck.otproxy.protocol.iec61850.NoOpClientEventListener;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end IEC 61850 proxy test: a fake IED ({@code iec61850bean} ServerSap) is fronted by the
 * proxy; a client operates the circuit breaker through the proxy. Verifies that allowed controls
 * are forwarded to the IED, denied controls return an MMS ServiceError, and the read allow-list
 * prunes non-allow-listed objects from the exposed model.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Iec61850ProxyIntegrationTest {

    private static final String XCBR_POS = "RelayIEDPROT/XCBR1.Pos";
    private static final String XCBR_CTLVAL = "RelayIEDPROT/XCBR1.Pos.Oper.ctlVal";
    private static final String CSWI_POS = "RelayIEDPROT/CSWI1.Pos";

    private final AtomicReference<Boolean> iedReceivedCtlVal = new AtomicReference<>();

    private ServerSap fakeIed;
    private Iec61850ProxyServer allowProxy; // XCBR1.Pos write allowed
    private Iec61850ProxyServer denyProxy; // XCBR1.Pos write denied
    private int allowProxyPort;
    private int denyProxyPort;

    @BeforeAll
    void setUp() throws Exception {
        String icdPath = Path.of(getClass().getResource("/test-relay.icd").toURI()).toString();

        // --- Fake IED: records the ctlVal it receives from a control operate ---
        ServerModel iedModel = SclParser.parse(icdPath).get(0);
        int iedPort = findFreePort();
        fakeIed = new ServerSap(iedPort, 0, null, iedModel, null);
        fakeIed.startListening(
                new ServerEventListener() {
                    @Override
                    public List<ServiceError> write(List<BasicDataAttribute> bdas) {
                        for (BasicDataAttribute bda : bdas) {
                            if (bda.getReference().toString().equals(XCBR_CTLVAL)
                                    && bda instanceof BdaBoolean b) {
                                iedReceivedCtlVal.set(b.getValue());
                            }
                        }
                        return null; // accept
                    }

                    @Override
                    public void serverStoppedListening(ServerSap serverSap) {}
                });

        allowProxyPort = findFreePort();
        allowProxy = startProxy(iedPort, allowProxyPort, true);

        denyProxyPort = findFreePort();
        denyProxy = startProxy(iedPort, denyProxyPort, false);
    }

    @AfterAll
    void tearDown() {
        if (allowProxy != null) allowProxy.stop();
        if (denyProxy != null) denyProxy.stop();
        if (fakeIed != null) fakeIed.stop();
    }

    @BeforeEach
    void reset() {
        iedReceivedCtlVal.set(null);
    }

    @Test
    void allowedOperateIsForwardedToIed() throws Exception {
        operate(allowProxyPort, false);
        assertThat(iedReceivedCtlVal.get()).isFalse();
    }

    @Test
    void deniedOperateReturnsServiceErrorAndIsNotForwarded() throws Exception {
        ClientAssociation association = associate(denyProxyPort);
        try {
            ServerModel model = association.retrieveModel();
            BdaBoolean ctlVal = (BdaBoolean) model.findModelNode(XCBR_CTLVAL, Fc.CO);
            ctlVal.setValue(false);
            FcModelNode pos = (FcModelNode) model.findModelNode(XCBR_POS, Fc.CO);

            assertThatThrownBy(() -> association.operate(pos)).isInstanceOf(ServiceError.class);
            assertThat(iedReceivedCtlVal.get()).isNull(); // never reached the IED
        } finally {
            association.close();
        }
    }

    @Test
    void enhancedSecurityControlIsExposedAsNormalSecurity() throws Exception {
        ClientAssociation association = associate(allowProxyPort);
        try {
            ServerModel model = association.retrieveModel();
            association.getAllDataValues();

            // The fake IED defines CSWI1.Pos as sbo-with-enhanced-security (ctlModel 4) with SBOw.
            // The proxy must expose it as sbo-with-normal-security (2) with a plain SBO instead,
            // so iec61850bean-based downstream clients can select-before-operate it.
            BdaInt8 ctlModel = (BdaInt8) model.findModelNode(CSWI_POS + ".ctlModel", Fc.CF);
            assertThat(ctlModel.getValue()).isEqualTo((byte) 2);
            assertThat(model.findModelNode(CSWI_POS + ".SBO", Fc.CO)).isNotNull();
            assertThat(model.findModelNode(CSWI_POS + ".SBOw", Fc.CO)).isNull();
        } finally {
            association.close();
        }
    }

    @Test
    void readAllowListPrunesNonAllowlistedObjects() throws Exception {
        ClientAssociation association = associate(allowProxyPort);
        try {
            ServerModel model = association.retrieveModel();
            // XCBR1.Pos is allow-listed -> present
            assertThat(model.findModelNode(XCBR_POS, Fc.ST)).isNotNull();
            // MMXU1 is not allow-listed -> pruned from the exposed model
            assertThat(model.findModelNode("RelayIEDPROT/MMXU1.TotW.mag.f", Fc.MX)).isNull();
        } finally {
            association.close();
        }
    }

    // --- Helpers ---

    private void operate(int proxyPort, boolean ctlVal) throws Exception {
        ClientAssociation association = associate(proxyPort);
        try {
            ServerModel model = association.retrieveModel();
            BdaBoolean ctlValNode = (BdaBoolean) model.findModelNode(XCBR_CTLVAL, Fc.CO);
            ctlValNode.setValue(ctlVal);
            FcModelNode pos = (FcModelNode) model.findModelNode(XCBR_POS, Fc.CO);
            association.operate(pos);
        } finally {
            association.close();
        }
    }

    private ClientAssociation associate(int port) throws Exception {
        return new ClientSap()
                .associate(
                        InetAddress.getByName("127.0.0.1"),
                        port,
                        null,
                        new NoOpClientEventListener());
    }

    private Iec61850ProxyServer startProxy(int iedPort, int proxyPort, boolean allowWrite)
            throws Exception {
        Iec61850Upstream upstream = new Iec61850Upstream("127.0.0.1", iedPort);
        upstream.connect();

        ProxyConfig config = config(allowWrite);
        RequestPipeline pipeline = new RequestPipeline(new RuleEngine(config), List.of());
        Iec61850ProxyServer server =
                new Iec61850ProxyServer(proxyPort, pipeline, upstream, config.getRules());
        server.start();
        return server;
    }

    private ProxyConfig config(boolean allowWrite) {
        Iec61850PointRuleConfig pos = new Iec61850PointRuleConfig();
        pos.setReference(XCBR_POS);
        pos.setAllowWrite(allowWrite);
        pos.setAllowRead(true);

        Iec61850PointRuleConfig cswiPos = new Iec61850PointRuleConfig();
        cswiPos.setReference(CSWI_POS);
        cswiPos.setAllowWrite(allowWrite);
        cswiPos.setAllowRead(true);

        RulesConfig rules = new RulesConfig();
        rules.setDefaultAction("DENY");
        rules.setObjects(List.of(pos, cswiPos));

        ProxyConfig config = new ProxyConfig();
        config.setProtocol("iec61850");
        config.setRules(rules);
        return config;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
