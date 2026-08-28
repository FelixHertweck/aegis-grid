package de.felixhertweck.emulator.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.ServerEventListener;
import com.beanit.iec61850bean.ServerSap;
import com.beanit.iec61850bean.ServiceError;
import de.felixhertweck.emulator.model.Iec61850References;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BreakerControlListener implements ServerEventListener {

    private static final Logger logger = LoggerFactory.getLogger(BreakerControlListener.class);
    private final BreakerStateChanged callback;

    public BreakerControlListener(BreakerStateChanged callback) {
        this.callback = callback;
    }

    @Override
    public List<ServiceError> write(List<BasicDataAttribute> bdas) {
        logger.info("Received write request:");
        // The server only honours the result list when it has one entry per written BDA, so it is
        // pre-filled with nulls (= success) and only rejected entries are replaced.
        List<ServiceError> errors = new ArrayList<>(Collections.nCopies(bdas.size(), null));
        boolean rejected = false;
        for (int i = 0; i < bdas.size(); i++) {
            BasicDataAttribute bda = bdas.get(i);
            logger.info(" - {} : {}", bda.getReference(), bda.getValueString());
            String reference = bda.getReference().toString();

            // Control path on the physical SIPROTEC: CSWI1.Pos, not XCBR1.Pos (status-only there).
            if (Iec61850References.CSWI_POS_OPER_CTLVAL.equals(reference)) {
                if (bda instanceof BdaBoolean bdaBoolean) {
                    boolean command = bdaBoolean.getValue();
                    logger.info(
                            "Received command to {} the circuit breaker (via CSWI1.Pos).",
                            command ? "CLOSE" : "OPEN");
                    callback.onBreakerCommand(command);
                }
            } else if (Iec61850References.XCBR_POS_OPER_CTLVAL.equals(reference)) {
                logger.warn(
                        "Rejected control write to {} — XCBR1.Pos is status-only, control must go"
                                + " through CSWI1.Pos.",
                        reference);
                errors.set(
                        i,
                        new ServiceError(
                                ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE,
                                "XCBR1.Pos is status-only — use CSWI1.Pos"));
                rejected = true;
            }
        }
        return rejected ? errors : null; // null indicates success
    }

    @Override
    public void serverStoppedListening(ServerSap serverSap) {
        logger.info("Server stopped listening.");
    }

    @FunctionalInterface
    public interface BreakerStateChanged {
        void onBreakerCommand(boolean close);
    }
}
