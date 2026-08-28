package de.felixhertweck.otproxy.protocol.iec61850;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ProxyControlModelSupport;
import com.beanit.iec61850bean.ServerModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites enhanced-security controls (ctlModel 3/4) in the model the proxy exposes downstream to
 * their normal-security equivalents (1/2), because {@code iec61850bean}'s server implements only
 * the normal-security control state machine (ctlModel 1 and 2).
 *
 * <p>Downstream-only: the upstream model keeps its real ctlModel, so {@link
 * Iec61850Upstream#forwardControl} still drives the physical IED with the correct SelectWithValue /
 * Operate sequence.
 *
 * <p>For select-before-operate (4 → 2) the scalar alone is not enough — the server starts a Select
 * only on a read of a child literally named {@code SBO}, where enhanced security carries {@code
 * SBOw} — so the exposed control object is made structurally consistent too.
 *
 * <p>Limitation (see {@code ot-proxy/README.md}): the {@code LastApplError} AddCause and the
 * asynchronous {@code CommandTermination} are not conveyed downstream.
 */
final class Iec61850ControlModelNormalizer {

    private static final Logger log = LoggerFactory.getLogger(Iec61850ControlModelNormalizer.class);

    private Iec61850ControlModelNormalizer() {}

    static void normalize(ServerModel exposedModel) {
        for (BasicDataAttribute bda : exposedModel.getBasicDataAttributes()) {
            if (!"ctlModel".equals(bda.getName()) || bda.getFc() != Fc.CF) continue;
            if (!(bda instanceof BdaInt8 ctlModelBda)) continue;

            byte val = ctlModelBda.getValue();
            boolean selectBeforeOperate;
            if (val == 3) {
                ctlModelBda.setValue((byte) 1);
                selectBeforeOperate = false;
            } else if (val == 4) {
                ctlModelBda.setValue((byte) 2);
                selectBeforeOperate = true;
            } else {
                continue;
            }

            String doRef = bda.getParent().getReference().toString();
            ModelNode controlCo = exposedModel.findModelNode(doRef, Fc.CO);
            if (controlCo instanceof FcModelNode co) {
                if (selectBeforeOperate) {
                    ProxyControlModelSupport.ensureSboAttribute(co);
                }
                ProxyControlModelSupport.removeChild(co, "SBOw");
                ModelNode oper = co.getChild("Oper");
                if (oper != null) {
                    ProxyControlModelSupport.removeChild(oper, "operTm");
                }
            }
            log.info(
                    "Normalised enhanced-security control at {} (ctlModel {} -> {})",
                    doRef,
                    val,
                    ctlModelBda.getValue());
        }
    }
}
