package de.felixhertweck.emulator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServerSap;
import de.felixhertweck.emulator.model.Iec61850References;
import de.felixhertweck.emulator.server.BreakerControlListener;
import de.felixhertweck.emulator.server.ManagementHttpServer;
import de.felixhertweck.emulator.service.IcdFileLoader;
import de.felixhertweck.emulator.service.ModelNodeWriter;
import de.felixhertweck.emulator.simulation.MeasurementGenerator;
import de.felixhertweck.emulator.simulation.ProtectionSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtectionRelayEmulator {

    private static final Logger logger = LoggerFactory.getLogger(ProtectionRelayEmulator.class);

    private final int port;
    private final int restPort;
    private final IcdFileLoader icdLoader = new IcdFileLoader();
    private final AtomicBoolean breakerClosed = new AtomicBoolean(true);

    private ServerSap serverSap;
    private ServerModel serverModel;
    private ModelNodeWriter writer;
    private ScheduledExecutorService scheduler;
    private MeasurementGenerator measurements;
    private ProtectionSimulator protection;
    private ManagementHttpServer resetHttpServer;

    public ProtectionRelayEmulator(int port, int restPort) {
        this.port = port;
        this.restPort = restPort;
    }

    public void start() throws Exception {
        String icdPath = icdLoader.extractToTemp();
        serverModel = SclParser.parse(icdPath).get(0);

        initCtlModel();

        serverSap = new ServerSap(port, 0, null, serverModel, null);
        writer = new ModelNodeWriter(serverModel, serverSap);

        BreakerControlListener listener = new BreakerControlListener(this::onBreakerCommand);
        serverSap.startListening(listener);

        logger.info("Protection Relay Emulator started and listening on port {}", port);

        resetHttpServer = new ManagementHttpServer(restPort, this::reset, this::getStatusJson);
        resetHttpServer.start();

        // Publish the initial breaker position
        publishInitialBreakerState();

        // Start dynamic simulations
        scheduler = Executors.newScheduledThreadPool(2);
        measurements = new MeasurementGenerator(writer, breakerClosed::get);
        // The protection function has direct authority over its own breaker — a trip bypasses
        // the CSWI1 Select/Operate gate, which only applies to external MMS clients.
        protection = new ProtectionSimulator(writer, () -> onBreakerCommand(false));

        measurements.scheduleOn(scheduler);
        protection.scheduleOn(scheduler);
    }

    public void stop() {
        if (protection != null) {
            protection.cancelPending();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (resetHttpServer != null) {
            resetHttpServer.stop();
        }
        if (serverSap != null) {
            serverSap.stop();
            logger.info("Protection Relay Emulator stopped.");
        }
    }

    private String getStatusJson() {
        boolean closed = breakerClosed.get();
        Boolean ptocStart = writer.readBoolean(Iec61850References.PTOC_STR_GENERAL, Fc.ST);
        Boolean ptocOperate = writer.readBoolean(Iec61850References.PTOC_OP_GENERAL, Fc.ST);
        float hz = writer.readFloat32(Iec61850References.MMXU_HZ_MAG_F);
        float totW = writer.readFloat32(Iec61850References.MMXU_TOTW_MAG_F);
        float iA = writer.readFloat32(Iec61850References.MMXU_A_PHSA_MAG_F);
        float iB = writer.readFloat32(Iec61850References.MMXU_A_PHSB_MAG_F);
        float iC = writer.readFloat32(Iec61850References.MMXU_A_PHSC_MAG_F);
        float uAB = writer.readFloat32(Iec61850References.MMXU_PPV_PHSAB_MAG_F);
        float uBC = writer.readFloat32(Iec61850References.MMXU_PPV_PHSBC_MAG_F);
        float uCA = writer.readFloat32(Iec61850References.MMXU_PPV_PHSCA_MAG_F);
        return "{"
                + "\"breakerClosed\":"
                + closed
                + ","
                + "\"ptocStart\":"
                + String.valueOf(ptocStart)
                + ","
                + "\"ptocOperate\":"
                + String.valueOf(ptocOperate)
                + ","
                + "\"frequencyHz\":"
                + f(hz)
                + ","
                + "\"totalPowerW\":"
                + f(totW)
                + ","
                + "\"currentA\":{"
                + "\"phsA\":"
                + f(iA)
                + ","
                + "\"phsB\":"
                + f(iB)
                + ","
                + "\"phsC\":"
                + f(iC)
                + "},"
                + "\"voltageV\":{"
                + "\"phsAB\":"
                + f(uAB)
                + ","
                + "\"phsBC\":"
                + f(uBC)
                + ","
                + "\"phsCA\":"
                + f(uCA)
                + "}"
                + "}";
    }

    private static String f(float v) {
        return Float.isFinite(v) ? String.valueOf(v) : "null";
    }

    void reset() {
        if (protection != null) {
            // Cancel any in-flight fault operate/clear tasks — otherwise a pending trip could
            // reopen the breaker after this reset already closed it.
            protection.cancelPending();
        }
        breakerClosed.set(true);
        writer.writeBreakerState(Iec61850References.XCBR_POS_STVAL, true);
        writer.writeBoolean(Iec61850References.PTOC_STR_GENERAL, Fc.ST, false);
        writer.writeBoolean(Iec61850References.PTOC_OP_GENERAL, Fc.ST, false);
        logger.info("Emulator state reset: circuit breaker closed, PTOC indicators cleared.");
    }

    public ServerModel getServerModel() {
        return serverModel;
    }

    /** Returns the current logical state of the circuit breaker. Package-private for tests. */
    boolean isBreakerClosed() {
        return breakerClosed.get();
    }

    /**
     * Applies a breaker open/close command and updates the XCBR model node. Package-private for
     * tests.
     */
    void triggerBreakerCommand(boolean close) {
        onBreakerCommand(close);
    }

    /**
     * Directly triggers a simulated protection fault, bypassing the random timer. Package-private
     * for tests.
     */
    void triggerProtectionFault() {
        protection.simulateFault();
    }

    private void initCtlModel() {
        // XCBR1.Pos is status-only on the physical SIPROTEC — not directly controllable.
        setCtlModel(Iec61850References.XCBR_POS_CTL_MODEL, (byte) 0, "XCBR1.Pos");
        // CSWI1.Pos is the actual control path. The physical SIPROTEC uses ctlModel 4, but this
        // emulator serves 2 — matching the proxy's downstream view, which is what an agent sees
        // (see Iec61850ControlModelNormalizer for why enhanced security is normalised away).
        setCtlModel(Iec61850References.CSWI_POS_CTL_MODEL, (byte) 2, "CSWI1.Pos");
    }

    private void setCtlModel(String reference, byte ctlModel, String label) {
        FcModelNode node = (FcModelNode) serverModel.findModelNode(reference, Fc.CF);
        if (node instanceof BdaInt8 bda) {
            bda.setValue(ctlModel);
        } else {
            logger.warn(
                    "Could not initialize ctlModel for {} — node not found or wrong type", label);
        }
    }

    private void onBreakerCommand(boolean close) {
        breakerClosed.set(close);
        writer.writeBreakerState(Iec61850References.XCBR_POS_STVAL, close);
    }

    private void publishInitialBreakerState() {
        writer.writeBreakerState(Iec61850References.XCBR_POS_STVAL, breakerClosed.get());
    }

    public static void main(String[] args) {
        try {
            int port = 10102;
            String portEnv = System.getenv("IEC61850_PORT");
            if (portEnv != null) {
                port = Integer.parseInt(portEnv);
            }

            int restPort = 8080;
            String restPortEnv = System.getenv("REST_PORT");
            if (restPortEnv != null) {
                restPort = Integer.parseInt(restPortEnv);
            }

            ProtectionRelayEmulator emulator = new ProtectionRelayEmulator(port, restPort);
            emulator.start();

            Runtime.getRuntime().addShutdownHook(new Thread(emulator::stop));

            // Block main thread until interrupted
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                logger.info("Main thread interrupted, shutting down...");
            }
        } catch (Exception e) {
            logger.error("Failed to start emulator", e);
        }
    }
}
