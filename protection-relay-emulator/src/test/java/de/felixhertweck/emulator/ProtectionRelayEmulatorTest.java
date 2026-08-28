package de.felixhertweck.emulator;

import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.beanit.iec61850bean.BdaBitString;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaFloat32;
import com.beanit.iec61850bean.BdaInt8;
import com.beanit.iec61850bean.ClientAssociation;
import com.beanit.iec61850bean.ClientSap;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.ServiceError;
import de.felixhertweck.emulator.model.Iec61850References;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProtectionRelayEmulatorTest {

    private ProtectionRelayEmulator emulator;
    private int port;

    private int findFreePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();
        int restPort = findFreePort();
        emulator = new ProtectionRelayEmulator(port, restPort);
        emulator.start();
    }

    @AfterEach
    void tearDown() {
        if (emulator != null) {
            emulator.stop();
        }
    }

    @Test
    void testServerModelLoadedAndContainsNodes() {
        ServerModel model = emulator.getServerModel();
        assertNotNull(model, "ServerModel should not be null");

        FcModelNode hzNode =
                (FcModelNode) model.findModelNode(Iec61850References.MMXU_HZ_MAG_F, Fc.MX);
        assertNotNull(hzNode, "Hz node should exist");

        FcModelNode posNode =
                (FcModelNode) model.findModelNode(Iec61850References.XCBR_POS_STVAL, Fc.ST);
        assertNotNull(posNode, "XCBR Pos stVal node should exist");
    }

    @Test
    void testPtocNodesExist() {
        ServerModel model = emulator.getServerModel();

        FcModelNode strNode =
                (FcModelNode) model.findModelNode(Iec61850References.PTOC_STR_GENERAL, Fc.ST);
        assertNotNull(strNode, "PTOC Str.general node should exist");

        FcModelNode opNode =
                (FcModelNode) model.findModelNode(Iec61850References.PTOC_OP_GENERAL, Fc.ST);
        assertNotNull(opNode, "PTOC Op.general node should exist");
    }

    @Test
    void testBreakerCommandChangesState() {
        assertTrue(emulator.isBreakerClosed(), "Breaker should start closed");

        emulator.triggerBreakerCommand(false);
        assertFalse(emulator.isBreakerClosed(), "Breaker should be open after OPEN command");

        // Verify XCBR model node reflects the open state (0x40 = off/open in Dbpos encoding)
        FcModelNode posNode =
                (FcModelNode)
                        emulator.getServerModel()
                                .findModelNode(Iec61850References.XCBR_POS_STVAL, Fc.ST);
        assertNotNull(posNode);
        BdaBitString bda = (BdaBitString) posNode;
        assertTrue((bda.getValue()[0] & 0xFF) == 0x40, "XCBR Pos should reflect OPEN state (0x40)");

        emulator.triggerBreakerCommand(true);
        assertTrue(emulator.isBreakerClosed(), "Breaker should be closed after CLOSE command");

        // Verify closed state (0x80 = on/closed in Dbpos encoding)
        assertTrue(
                (bda.getValue()[0] & 0xFF) == 0x80, "XCBR Pos should reflect CLOSED state (0x80)");
    }

    @Test
    void testBreakerStateAffectsMmxuCurrents() throws InterruptedException {
        ServerModel model = emulator.getServerModel();
        BdaFloat32 totW =
                (BdaFloat32) model.findModelNode(Iec61850References.MMXU_TOTW_MAG_F, Fc.MX);
        assertNotNull(totW);

        // Wait for first measurement cycle with breaker closed (~1000 W)
        boolean seenHighValue = false;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            Float v = totW.getFloat();
            if (v != null && v > 100f) {
                seenHighValue = true;
                break;
            }
        }
        assertTrue(seenHighValue, "TotW should exceed 100 W with breaker closed");

        emulator.triggerBreakerCommand(false);

        // Wait for next measurement cycle — multiplier drops to 0.001, so TotW ≈ 1 W
        boolean seenLowValue = false;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            Float v = totW.getFloat();
            if (v != null && v < 5f) {
                seenLowValue = true;
                break;
            }
        }
        assertTrue(seenLowValue, "TotW should drop below 5 W with breaker open");
    }

    @Test
    void testCswi1PosCtlModelIsSboWithNormalSecurity() {
        FcModelNode node =
                (FcModelNode)
                        emulator.getServerModel()
                                .findModelNode(Iec61850References.CSWI_POS_CTL_MODEL, Fc.CF);
        assertNotNull(node, "CSWI1.Pos ctlModel node should exist");
        assertEquals(
                (byte) 2,
                ((BdaInt8) node).getValue(),
                "ctlModel must be 2 (sbo-with-normal-security) — the actual control path on the"
                        + " physical SIPROTEC");
    }

    @Test
    void testXcbr1PosCtlModelIsStatusOnly() {
        FcModelNode node =
                (FcModelNode)
                        emulator.getServerModel()
                                .findModelNode(Iec61850References.XCBR_POS_CTL_MODEL, Fc.CF);
        assertNotNull(node, "XCBR1.Pos ctlModel node should exist");
        assertEquals(
                (byte) 0,
                ((BdaInt8) node).getValue(),
                "ctlModel must be 0 (status-only) — matches the physical SIPROTEC, where XCBR1 is"
                        + " not directly controllable");
    }

    @Test
    void testSelectOperateViaCswi1OpensBreakerAndUpdatesXcbr1Pos() throws Exception {
        assertTrue(emulator.isBreakerClosed(), "Breaker should start closed");

        ClientSap clientSap = new ClientSap();
        ClientAssociation association =
                clientSap.associate(InetAddress.getByName("127.0.0.1"), port, null, null);
        try {
            ServerModel clientModel = association.retrieveModel();

            FcModelNode posNode =
                    (FcModelNode) clientModel.findModelNode("SIP1CB1/CSWI1.Pos", Fc.CO);
            assertNotNull(posNode, "CSWI1 Pos (CO) node should exist in client model");

            BdaBoolean ctlVal =
                    (BdaBoolean)
                            clientModel.findModelNode(
                                    Iec61850References.CSWI_POS_OPER_CTLVAL, Fc.CO);
            assertNotNull(ctlVal, "CSWI1 Pos.Oper.ctlVal node should exist");
            ctlVal.setValue(false);

            boolean selected = association.select(posNode);
            assertTrue(selected, "Select on CSWI1.Pos should succeed (sbo-with-normal-security)");

            association.operate(posNode);

            Thread.sleep(100);
        } finally {
            association.close();
        }

        assertFalse(emulator.isBreakerClosed(), "Breaker should be open after operate command");

        FcModelNode posNode =
                (FcModelNode)
                        emulator.getServerModel()
                                .findModelNode(Iec61850References.XCBR_POS_STVAL, Fc.ST);
        assertTrue(
                (((BdaBitString) posNode).getValue()[0] & 0xFF) == 0x40,
                "XCBR Pos.stVal should reflect OPEN state (0x40)");
    }

    @Test
    void testDirectOperateOnXcbr1PosIsRejected() throws Exception {
        assertTrue(emulator.isBreakerClosed(), "Breaker should start closed");

        ClientSap clientSap = new ClientSap();
        ClientAssociation association =
                clientSap.associate(InetAddress.getByName("127.0.0.1"), port, null, null);
        try {
            ServerModel clientModel = association.retrieveModel();

            FcModelNode posNode =
                    (FcModelNode) clientModel.findModelNode("SIP1CB1/XCBR1.Pos", Fc.CO);
            assertNotNull(posNode, "XCBR1 Pos (CO) node should exist in client model");

            BdaBoolean ctlVal =
                    (BdaBoolean)
                            clientModel.findModelNode(
                                    Iec61850References.XCBR_POS_OPER_CTLVAL, Fc.CO);
            assertNotNull(ctlVal, "XCBR1 Pos.Oper.ctlVal node should exist");
            ctlVal.setValue(false);

            // XCBR1.Pos.ctlModel is status-only (0) — a direct operate is rejected by the
            // library's own control-service state machine before it ever reaches the write
            // listener, so this should throw rather than succeed.
            assertThrows(ServiceError.class, () -> association.operate(posNode));

            Thread.sleep(100);
        } finally {
            association.close();
        }

        assertTrue(
                emulator.isBreakerClosed(),
                "Breaker must remain closed — the operate on the status-only XCBR1.Pos must not"
                        + " have taken effect");
    }

    @Test
    void testDirectWriteToXcbr1PosOperIsRejected() throws Exception {
        assertTrue(emulator.isBreakerClosed(), "Breaker should start closed");

        ClientSap clientSap = new ClientSap();
        ClientAssociation association =
                clientSap.associate(InetAddress.getByName("127.0.0.1"), port, null, null);
        try {
            ServerModel clientModel = association.retrieveModel();

            BdaBoolean ctlVal =
                    (BdaBoolean)
                            clientModel.findModelNode(
                                    Iec61850References.XCBR_POS_OPER_CTLVAL, Fc.CO);
            assertNotNull(ctlVal, "XCBR1 Pos.Oper.ctlVal node should exist");
            ctlVal.setValue(false);

            // End-to-end guard: a raw SetDataValues on XCBR1.Pos must never move the breaker.
            // The library rejects FC=CO writes below "Oper" before the write listener sees them,
            // so this covers the outer behaviour; BreakerControlListenerTest covers the listener's
            // own rejection.
            assertThrows(ServiceError.class, () -> association.setDataValues(ctlVal));

            Thread.sleep(100);
        } finally {
            association.close();
        }

        assertTrue(
                emulator.isBreakerClosed(),
                "Breaker must remain closed — a direct write to the status-only XCBR1.Pos must not"
                        + " have taken effect");
    }

    @Test
    void testProtectionFaultTripsBreaker() throws InterruptedException {
        assertTrue(emulator.isBreakerClosed(), "Breaker should start closed");

        emulator.triggerProtectionFault();

        // Default operateDelaySeconds = 1s; poll up to 3s for the trip to propagate.
        boolean tripped = false;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(100);
            if (!emulator.isBreakerClosed()) {
                tripped = true;
                break;
            }
        }
        assertTrue(tripped, "PTOC operate should trip the breaker open");

        FcModelNode posNode =
                (FcModelNode)
                        emulator.getServerModel()
                                .findModelNode(Iec61850References.XCBR_POS_STVAL, Fc.ST);
        assertTrue(
                (((BdaBitString) posNode).getValue()[0] & 0xFF) == 0x40,
                "XCBR Pos.stVal should reflect OPEN state (0x40) after the trip");
    }

    @Test
    void testDynamicSimulationUpdatesMeasurements() throws InterruptedException {
        ServerModel model = emulator.getServerModel();
        FcModelNode totWNode =
                (FcModelNode) model.findModelNode(Iec61850References.MMXU_TOTW_MAG_F, Fc.MX);
        assertNotNull(totWNode);

        BdaFloat32 bda = (BdaFloat32) totWNode;
        Float initialValue = bda.getFloat();
        float initial = initialValue == null ? 0.0f : initialValue;

        boolean valueChanged = false;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(100);
            Float newValue = bda.getFloat();
            if (newValue != null && Math.abs(newValue - initial) > 1.0e-3f) {
                valueChanged = true;
                break;
            }
        }

        assertTrue(valueChanged, "Value should have been updated by dynamic simulation");
    }
}
