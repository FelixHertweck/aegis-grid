package de.felixhertweck.inverter;

import static org.assertj.core.api.Assertions.assertThat;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import java.io.IOException;
import java.net.Socket;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class InverterEmulatorTest {

    private static Thread emulatorThread;
    private static final int TEST_PORT = 10502; // Using unprivileged port

    @BeforeAll
    public static void setup() throws Exception {
        emulatorThread =
                new Thread(
                        () -> {
                            InverterEmulator.main(new String[] {String.valueOf(TEST_PORT)});
                        });
        emulatorThread.setDaemon(true);
        emulatorThread.start();

        // Poll until the emulator port is reachable (max 5 seconds)
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
                break;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
    }

    @AfterAll
    public static void teardown() {
        emulatorThread.interrupt();
    }

    private static int readU32(ModbusTCPMaster master, int offset) throws Exception {
        InputRegister[] regs = master.readInputRegisters(1, offset, 2);
        return (regs[0].getValue() << 16) | regs[1].getValue();
    }

    @Test
    public void testEmergencyStopLogic() throws Exception {
        ModbusTCPMaster master = new ModbusTCPMaster("127.0.0.1", TEST_PORT);
        master.connect();

        // 1. Check initial state
        assertThat(readU32(master, 30200)).isEqualTo(307); // Operation.Health: Ok
        assertThat(readU32(master, 30880)).isEqualTo(1780); // Operation.PvGriConn: connected

        // 2. Trigger E-Stop: write Inverter.FstStop (40018) = 1749 (Full stop)
        master.writeMultipleRegisters(
                1, 40017, new Register[] {new SimpleRegister(0), new SimpleRegister(1749)});

        // 3. Poll until E-Stop takes effect (max 3 seconds)
        long deadline = System.currentTimeMillis() + 3000;
        int health = readU32(master, 30200);
        while (health != 35 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            health = readU32(master, 30200);
        }
        assertThat(health).isEqualTo(35); // Operation.Health: Fault

        // 4. Physical relationship: AC power/DC current collapse, but the grid voltage — which
        // is external to this inverter — stays present. This is the exact coupling that was
        // previously missing (power/health were independent hardcoded writes).
        assertThat(readU32(master, 30774)).isEqualTo(0); // GridMs.TotW
        assertThat(readU32(master, 30768)).isEqualTo(0); // DcMs.Amp
        assertThat(readU32(master, 30880)).isEqualTo(1779); // Operation.PvGriConn: Separated
        // GridMs.PhV.phsA (grid voltage) is external to the inverter and stays present
        assertThat(readU32(master, 30782) / 100.0).isCloseTo(230.0, Offset.offset(5.0));

        master.disconnect();
    }
}
