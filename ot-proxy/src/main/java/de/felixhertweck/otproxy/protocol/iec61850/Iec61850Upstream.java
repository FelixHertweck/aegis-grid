package de.felixhertweck.otproxy.protocol.iec61850;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Locale;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaInt8U;
import com.beanit.iec61850bean.BdaTimestamp;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientSap;
import com.beanit.iec61850bean.ConstructedDataAttribute;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MMS client side of the IEC 61850 proxy: a single, long-lived association to the real upstream
 * IED.
 *
 * <p>Unlike {@code ModbusTcpUpstream} (which opens a socket per call), an MMS association is
 * stateful and expensive to establish, so we keep one open and serialize all service calls through
 * it. The retrieved {@link ServerModel} is the source of truth that the proxy mirrors to downstream
 * clients and against which control operations are forwarded.
 */
public class Iec61850Upstream {

    private static final Logger log = LoggerFactory.getLogger(Iec61850Upstream.class);

    private final String host;
    private final int port;

    private final Object lock = new Object();
    private ClientSap clientSap;
    private ClientAssociation association;
    private ServerModel model;

    public Iec61850Upstream(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Associates with the upstream IED and retrieves its data model (structure + initial values).
     */
    public void connect() throws IOException, ServiceError {
        synchronized (lock) {
            connectUnderLock();
            log.info("IEC 61850 upstream connected to {}:{}", host, port);
        }
    }

    private void connectUnderLock() throws IOException, ServiceError {
        clientSap = new ClientSap();
        association =
                clientSap.associate(
                        InetAddress.getByName(host), port, null, new NoOpClientEventListener());
        model = association.retrieveModel();
        association.getAllDataValues();
    }

    /** The mirrored upstream model. Never {@code null} once {@link #connect()} has succeeded. */
    public ServerModel getModel() {
        return model;
    }

    /** Refreshes all readable values from the IED into the mirrored model (best-effort). */
    public void refreshValues() {
        synchronized (lock) {
            if (association == null) return;
            try {
                association.getAllDataValues();
            } catch (IOException | ServiceError e) {
                log.warn("Upstream value poll failed: {} — reconnecting", e.getMessage());
                association.close();
                association = null;
                try {
                    connectUnderLock();
                    log.info("Reconnected to upstream IED at {}:{}", host, port);
                } catch (IOException | ServiceError re) {
                    log.warn(
                            "Reconnect to upstream failed, will retry next poll: {}",
                            re.getMessage());
                }
            }
        }
    }

    /**
     * Forwards a control command to the IED. {@code doRef} is the controllable data object (e.g.
     * {@code RelayIEDPROT/XCBR1.Pos}); the command is issued as a direct Operate, or Select +
     * Operate when the object's {@code ctlModel} is select-before-operate.
     *
     * @throws ServiceError if the IED rejects the command
     */
    public void forwardControl(String doRef, boolean ctlVal) throws IOException, ServiceError {
        synchronized (lock) {
            ModelNode ctlValNode = model.findModelNode(doRef + ".Oper.ctlVal", Fc.CO);
            if (!(ctlValNode instanceof BdaBoolean ctlValBda)) {
                throw new ServiceError(
                        ServiceError.INSTANCE_NOT_AVAILABLE,
                        "No boolean control value at " + doRef + ".Oper.ctlVal");
            }
            FcModelNode controlDo = (FcModelNode) model.findModelNode(doRef, Fc.CO);
            if (controlDo == null) {
                throw new ServiceError(
                        ServiceError.INSTANCE_NOT_AVAILABLE, "No controllable object at " + doRef);
            }

            ctlValBda.setValue(ctlVal);

            int ctlModelOrd = resolveCtlModelOrd(doRef);
            // The IED's failure reason (LastApplError AddCause) is not recoverable here —
            // iec61850bean's client discards the LastApplError report — so we only name which
            // phase failed and keep the IED's MMS error code. The message string does not cross
            // the downstream MMS boundary (clients see the numeric DataAccessError only); it is
            // for the proxy log.
            try {
                if (ctlModelOrd == 2) {
                    // sbo-with-normal-security: read SBO attribute to reserve the object
                    if (!association.select(controlDo)) {
                        throw new ServiceError(
                                ServiceError.CONTROL_MUST_BE_SELECTED,
                                "SBO returned empty (already selected by another client?)");
                    }
                } else if (ctlModelOrd == 4) {
                    // sbo-with-enhanced-security: SelectWithValues writes to SBOw (includes ctlVal)
                    selectWithValues(controlDo, ctlVal);
                }
            } catch (ServiceError e) {
                log.warn("Upstream Select failed for {}: {}", doRef, e.getMessage());
                throw new ServiceError(
                        e.getErrorCode(),
                        "upstream IED rejected Select for " + doRef + ": " + e.getMessage(),
                        e);
            }
            try {
                association.operate(controlDo);
            } catch (ServiceError e) {
                log.warn("Upstream Operate failed for {}: {}", doRef, e.getMessage());
                throw new ServiceError(
                        e.getErrorCode(),
                        "upstream IED rejected Operate for " + doRef + ": " + e.getMessage(),
                        e);
            }
            log.info("Forwarded control {}.Oper.ctlVal={} to upstream", doRef, ctlVal);
        }
    }

    /**
     * SelectWithValues for sbo-with-enhanced-security (ctlModel=4): writes control values to the
     * {@code SBOw} structure. Throws {@link ServiceError} if the IED rejects the reservation.
     */
    private void selectWithValues(FcModelNode controlDo, boolean ctlVal)
            throws ServiceError, IOException {
        ConstructedDataAttribute sbOw = (ConstructedDataAttribute) controlDo.getChild("SBOw");
        if (sbOw == null) {
            throw new ServiceError(
                    ServiceError.INSTANCE_NOT_AVAILABLE,
                    "No SBOw node for enhanced-security select at " + controlDo.getReference());
        }
        ((BdaBoolean) sbOw.getChild("ctlVal")).setValue(ctlVal);
        ((BdaInt8U) sbOw.getChild("ctlNum")).setValue((short) 1);
        ((BdaTimestamp) sbOw.getChild("T")).setInstant(Instant.now());
        association.setDataValues(sbOw);
    }

    /**
     * Returns the {@code ctlModel} ordinal for the given DO reference (FC=CF). Returns 1
     * (direct-with-normal-security) when the attribute is absent or unrecognised.
     *
     * <ul>
     *   <li>1 = direct-with-normal-security
     *   <li>2 = sbo-with-normal-security
     *   <li>3 = direct-with-enhanced-security
     *   <li>4 = sbo-with-enhanced-security
     * </ul>
     */
    private int resolveCtlModelOrd(String doRef) {
        ModelNode ctlModel = model.findModelNode(doRef + ".ctlModel", Fc.CF);
        if (!(ctlModel instanceof BasicDataAttribute bda)) return 1;
        String v = bda.getValueString();
        if (v == null) return 1;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("2") || v.contains("sbo-with-normal")) return 2;
        if (v.equals("4") || v.contains("sbo-with-enhanced")) return 4;
        if (v.equals("3") || v.contains("direct-with-enhanced")) return 3;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    public void close() {
        synchronized (lock) {
            if (association != null) {
                association.close();
                association = null;
            }
            log.info("IEC 61850 upstream closed.");
        }
    }
}
