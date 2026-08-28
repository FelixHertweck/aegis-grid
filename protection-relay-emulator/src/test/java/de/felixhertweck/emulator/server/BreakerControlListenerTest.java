package de.felixhertweck.emulator.server;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.ObjectReference;
import com.beanit.iec61850bean.ServiceError;
import de.felixhertweck.emulator.model.Iec61850References;
import org.junit.jupiter.api.Test;

class BreakerControlListenerTest {

    private static BdaBoolean ctlVal(String reference, boolean value) {
        BdaBoolean bda = new BdaBoolean(new ObjectReference(reference), Fc.CO, null, false, false);
        bda.setValue(value);
        return bda;
    }

    @Test
    void cswiWriteInvokesCallbackAndReportsSuccess() {
        Boolean[] received = new Boolean[1];
        BreakerControlListener listener = new BreakerControlListener(c -> received[0] = c);

        List<ServiceError> errors =
                listener.write(List.of(ctlVal(Iec61850References.CSWI_POS_OPER_CTLVAL, false)));

        assertNull(errors, "A successful write must be reported as null");
        assertEquals(Boolean.FALSE, received[0], "CSWI1.Pos write should reach the callback");
    }

    @Test
    void xcbrWriteIsRejectedWithOneResultPerWrittenAttribute() {
        Boolean[] received = new Boolean[1];
        BreakerControlListener listener = new BreakerControlListener(c -> received[0] = c);

        List<BasicDataAttribute> bdas =
                List.of(
                        ctlVal(Iec61850References.CSWI_POS_OPER_CTLVAL, true),
                        ctlVal(Iec61850References.XCBR_POS_OPER_CTLVAL, false));
        List<ServiceError> errors = listener.write(bdas);

        // The server discards the error list unless it has exactly one entry per written BDA
        // (null = success), so a short list would silently turn the rejection into a success.
        assertNotNull(errors, "A rejected write must report errors");
        assertEquals(bdas.size(), errors.size(), "One result per written attribute");
        assertNull(errors.get(0), "The CSWI write must stay successful");
        assertNotNull(errors.get(1), "The XCBR write must be rejected");
        assertEquals(
                ServiceError.ACCESS_NOT_ALLOWED_IN_CURRENT_STATE, errors.get(1).getErrorCode());
        assertEquals(Boolean.TRUE, received[0], "The CSWI write in the same request still applies");
    }
}
