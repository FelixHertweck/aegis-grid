package de.felixhertweck.otproxy.protocol.iec61850;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerModel;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link Iec61850ControlModelNormalizer}: the enhanced→normal security downgrade the
 * proxy applies to its exposed model. Covered here rather than end-to-end because {@code
 * iec61850bean}'s server cannot host an enhanced-security control at all, so a proxy→fake-IED
 * integration test of the 4→2 path is not possible.
 */
class Iec61850ControlModelNormalizerTest {

    private static final String CSWI_POS = "RelayIEDPROT/CSWI1.Pos";
    private static final String XCBR_POS = "RelayIEDPROT/XCBR1.Pos";

    private ServerModel parseModel() throws Exception {
        String icdPath = Path.of(getClass().getResource("/test-relay.icd").toURI()).toString();
        return SclParser.parse(icdPath).get(0);
    }

    private int ctlModel(ServerModel model, String doRef) {
        return ((BdaInt8) model.findModelNode(doRef + ".ctlModel", Fc.CF)).getValue();
    }

    @Test
    void sboEnhancedControlIsNormalisedToSboNormalWithConsistentStructure() throws Exception {
        ServerModel model = parseModel();

        // Precondition: the ICD defines CSWI1.Pos as sbo-with-enhanced-security with SBOw, no SBO.
        assertThat(ctlModel(model, CSWI_POS)).isEqualTo(4);
        assertThat(model.findModelNode(CSWI_POS + ".SBOw", Fc.CO)).isNotNull();
        assertThat(model.findModelNode(CSWI_POS + ".SBO", Fc.CO)).isNull();
        FcModelNode operBefore = (FcModelNode) model.findModelNode(CSWI_POS + ".Oper", Fc.CO);
        assertThat(operBefore.getChild("operTm")).isNotNull();

        Iec61850ControlModelNormalizer.normalize(model);

        // ctlModel downgraded, and the object is now a structurally valid sbo-normal control.
        assertThat(ctlModel(model, CSWI_POS)).isEqualTo(2);
        assertThat(model.findModelNode(CSWI_POS + ".SBO", Fc.CO)).isNotNull();
        assertThat(model.findModelNode(CSWI_POS + ".SBOw", Fc.CO)).isNull();
        FcModelNode operAfter = (FcModelNode) model.findModelNode(CSWI_POS + ".Oper", Fc.CO);
        assertThat(operAfter.getChild("operTm")).isNull();
        assertThat(operAfter.getChild("ctlVal")).isNotNull();
    }

    @Test
    void normalSecurityControlIsLeftUntouched() throws Exception {
        ServerModel model = parseModel();

        assertThat(ctlModel(model, XCBR_POS)).isEqualTo(1); // direct-with-normal-security

        Iec61850ControlModelNormalizer.normalize(model);

        assertThat(ctlModel(model, XCBR_POS)).isEqualTo(1);
    }
}
