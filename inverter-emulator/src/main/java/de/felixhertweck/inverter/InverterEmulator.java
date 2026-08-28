package de.felixhertweck.inverter;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import de.felixhertweck.inverter.server.InverterHttpServer;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SMA Modbus register addresses, types and TAGLIST values below are verified against the official
 * SMA Modbus Parameter and Measured Values list for the STP 15000TL-30 / 17000TL-30 / 20000TL-30 /
 * 25000TL-30 (firmware >= 2.83.03.R).
 */
public class InverterEmulator {

    private static final Logger log = LoggerFactory.getLogger(InverterEmulator.class);

    private static int PORT = 502;

    // --- Input registers (3xxxx, FC04, read-only telemetry); value = SMA register - 1 ---
    private static final int HEALTH_ADDR = 30200; // 30201, Operation.Health (U32)
    private static final int YIELD_ADDR = 30516; // 30517, Metering.DyWhOut (U64)
    private static final int DC_AMP_ADDR = 30768; // 30769, DcMs.Amp (S32, A, FIX3)
    private static final int DC_VOL_ADDR = 30770; // 30771, DcMs.Vol (S32, V, FIX2)
    private static final int DC_WATT_ADDR = 30772; // 30773, DcMs.Watt (S32, W, FIX0)
    private static final int POWER_ADDR = 30774; // 30775, GridMs.TotW (S32, W)
    private static final int AC_V_A_ADDR = 30782; // 30783, GridMs.PhV.phsA (U32, FIX2)
    private static final int AC_V_B_ADDR = 30784; // 30785, GridMs.PhV.phsB (U32, FIX2)
    private static final int AC_V_C_ADDR = 30786; // 30787, GridMs.PhV.phsC (U32, FIX2)
    private static final int AC_HZ_ADDR = 30802; // 30803, GridMs.Hz (U32, FIX2)
    private static final int GRID_CONN_ADDR = 30880; // 30881, Operation.PvGriConn (U32)

    // --- Holding registers (4xxxx, FC03/FC16, control) ---
    private static final int FSTOP_ADDR = 40017; // 40018, Inverter.FstStop (U32, WO)

    private static final int HEALTH_OK = 307; // Operation.Health: Ok
    private static final int HEALTH_FAULT = 35; // Operation.Health: Fault (Alm)

    private static final int GRID_CONNECTED = 1780; // Operation.PvGriConn: Public electricity mains
    private static final int GRID_SEPARATED = 1779; // Operation.PvGriConn: Separated

    private static final int FSTOP_START = 1467; // Inverter.FstStop: Start
    private static final int FSTOP_FULSTOP = 1749; // Inverter.FstStop: Full stop

    private static final double DC_VOLTAGE_NOMINAL_V = 620.0;
    private static final double AC_VOLTAGE_NOMINAL_V = 230.0;
    private static final double AC_FREQUENCY_NOMINAL_HZ = 50.0;
    private static final double EFFICIENCY = 0.97;
    private static final int basePower = 15000; // Target AC output under normal operation, in W

    // Register offsets to log on external FC04 reads, keyed by the first word of the register.
    private static final Map<Integer, String> LOGGED_INPUT_REGISTERS =
            Map.ofEntries(
                    Map.entry(HEALTH_ADDR, "health status"),
                    Map.entry(POWER_ADDR, "AC active power"),
                    Map.entry(YIELD_ADDR, "daily energy yield"),
                    Map.entry(DC_AMP_ADDR, "DC input current"),
                    Map.entry(DC_VOL_ADDR, "DC input voltage"),
                    Map.entry(DC_WATT_ADDR, "DC input power"),
                    Map.entry(AC_V_A_ADDR, "grid voltage phase A"),
                    Map.entry(AC_V_B_ADDR, "grid voltage phase B"),
                    Map.entry(AC_V_C_ADDR, "grid voltage phase C"),
                    Map.entry(AC_HZ_ADDR, "grid frequency"),
                    Map.entry(GRID_CONN_ADDR, "grid connection status"));

    private static SimpleProcessImage spi;
    private static volatile long dailyYieldWh = 0;

    // Set once the simulation thread starts; used to skip logging for internal register accesses.
    private static volatile Thread simulationThread;

    public static void main(String[] args) {
        if (args.length > 0) {
            PORT = Integer.parseInt(args[0]);
        }
        try {
            spi =
                    new SimpleProcessImage() {
                        @Override
                        public synchronized InputRegister getInputRegister(int ref) {
                            // Only log reads originating from external Modbus clients, not from the
                            // internal simulation loop.
                            if (simulationThread != null
                                    && Thread.currentThread() != simulationThread) {
                                String description = LOGGED_INPUT_REGISTERS.get(ref);
                                if (description != null) {
                                    log.info(
                                            "Modbus FC04 read: {} (register {})",
                                            description,
                                            ref + 1);
                                }
                            }
                            return super.getInputRegister(ref);
                        }
                    };

            // Input registers (FC04) for 3xxxx telemetry addresses (read-only via Modbus)
            for (int i = 0; i <= GRID_CONN_ADDR + 1; i++) {
                spi.addInputRegister(new SimpleInputRegister(0));
            }

            // Holding registers (FC03/FC16) for 4xxxx control addresses
            for (int i = 0; i <= FSTOP_ADDR + 1; i++) {
                spi.addRegister(new SimpleRegister(0));
            }

            // Initial values — normal operation, grid-connected
            writeInputU64(YIELD_ADDR, dailyYieldWh);
            applyElectricalModel(true);

            // Create and start Modbus TCP Slave
            ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(PORT, 5); // pool size 5
            slave.addProcessImage(1, spi); // default Unit ID 1
            slave.open();

            log.info("Inverter emulator started on port {}", PORT);

            int restPort = 8080;
            String restPortEnv = System.getenv("REST_PORT");
            if (restPortEnv != null) {
                try {
                    restPort = Integer.parseInt(restPortEnv);
                } catch (NumberFormatException e) {
                    log.warn(
                            "Invalid REST_PORT value '{}', using default {}",
                            restPortEnv,
                            restPort);
                }
            }
            InverterHttpServer httpServer =
                    new InverterHttpServer(
                            restPort, InverterEmulator::reset, InverterEmulator::getStatusJson);
            httpServer.start();

            // Simulation Engine — capture the thread reference at creation time so that
            // external FC04 reads during the initial 1s delay are already logged correctly.
            ScheduledExecutorService executor =
                    Executors.newSingleThreadScheduledExecutor(
                            r -> {
                                simulationThread = new Thread(r, "simulation-loop");
                                return simulationThread;
                            });
            executor.scheduleAtFixedRate(InverterEmulator::simulationLoop, 1, 1, TimeUnit.SECONDS);

            // Block until JVM shutdown
            CountDownLatch latch = new CountDownLatch(1);
            Runtime.getRuntime()
                    .addShutdownHook(
                            new Thread(
                                    () -> {
                                        httpServer.stop();
                                        latch.countDown();
                                    }));
            latch.await();
            executor.shutdown();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ModbusException | RuntimeException | java.io.IOException e) {
            log.error("Fatal error starting inverter emulator", e);
            System.exit(1);
        }
    }

    private static synchronized String getStatusJson() {
        boolean gridConnected = readInputU32(GRID_CONN_ADDR) == GRID_CONNECTED;
        int health = readInputU32(HEALTH_ADDR);
        String healthLabel =
                health == HEALTH_OK ? "OK" : health == HEALTH_FAULT ? "FAULT" : "UNKNOWN";
        int powerW = readInputU32(POWER_ADDR);
        double dcVoltageV = readInputU32(DC_VOL_ADDR) / 100.0;
        double dcCurrentA = readInputU32(DC_AMP_ADDR) / 1000.0;
        double acVoltageV = readInputU32(AC_V_A_ADDR) / 100.0;
        double acFrequencyHz = readInputU32(AC_HZ_ADDR) / 100.0;
        return "{"
                + "\"emergencyStop\":"
                + !gridConnected
                + ","
                + "\"health\":\""
                + healthLabel
                + "\","
                + "\"powerW\":"
                + powerW
                + ","
                + "\"dailyYieldWh\":"
                + dailyYieldWh
                + ","
                + "\"gridConnected\":"
                + gridConnected
                + ","
                + "\"dcVoltageV\":"
                + dcVoltageV
                + ","
                + "\"dcCurrentA\":"
                + dcCurrentA
                + ","
                + "\"acVoltageV\":"
                + acVoltageV
                + ","
                + "\"acFrequencyHz\":"
                + acFrequencyHz
                + "}";
    }

    private static synchronized void reset() {
        writeHoldingU32(FSTOP_ADDR, FSTOP_START);
        applyElectricalModel(true);
        log.info(
                "Inverter state reset: emergency stop cleared, health restored to OK({})",
                HEALTH_OK);
    }

    private static synchronized void simulationLoop() {
        try {
            boolean fulStop = readHoldingU32(FSTOP_ADDR) == FSTOP_FULSTOP;
            boolean wasConnected = readInputU32(HEALTH_ADDR) == HEALTH_OK;

            if (fulStop && wasConnected) {
                log.warn(
                        "EMERGENCY STOP triggered via Modbus write to register {} —"
                                + " shutting down inverter",
                        FSTOP_ADDR + 1);
            } else if (!fulStop && !wasConnected) {
                log.info("Inverter resumed normal operation.");
            }

            int acPowerW = applyElectricalModel(!fulStop);

            if (fulStop && wasConnected) {
                log.info(
                        "Emergency stop confirmed: power={}W, health=FAULT({})",
                        acPowerW,
                        HEALTH_FAULT);
            } else if (!fulStop) {
                long addedYield = acPowerW / 3600;
                if (addedYield == 0) addedYield = 1;
                dailyYieldWh += addedYield;
                writeInputU64(YIELD_ADDR, dailyYieldWh);
            }
        } catch (RuntimeException e) {
            log.error("Simulation loop error", e);
        }
    }

    /**
     * Derives every electrical register from a single {@code gridConnected} state. The AC grid is
     * external to the inverter and stays present either way — only what the inverter itself drives
     * (DC current, AC power) collapses to zero. Returns the resulting AC active power in W.
     */
    private static int applyElectricalModel(boolean gridConnected) {
        double dcVoltageV = DC_VOLTAGE_NOMINAL_V * (1 + (Math.random() - 0.5) * 0.01);
        double acVoltageV = AC_VOLTAGE_NOMINAL_V * (1 + (Math.random() - 0.5) * 0.01);
        double acFrequencyHz = AC_FREQUENCY_NOMINAL_HZ * (1 + (Math.random() - 0.5) * 0.002);

        double dcCurrentA = 0;
        double dcPowerW = 0;
        int acPowerW = 0;

        if (gridConnected) {
            double targetAcPowerW = basePower * (1 + (Math.random() - 0.5) * 0.026);
            double targetDcPowerW = targetAcPowerW / EFFICIENCY;
            dcCurrentA = targetDcPowerW / dcVoltageV;
            dcPowerW = dcVoltageV * dcCurrentA;
            acPowerW = (int) Math.round(dcPowerW * EFFICIENCY);
        }

        writeInputU32(DC_VOL_ADDR, (int) Math.round(dcVoltageV * 100)); // FIX2
        writeInputU32(DC_AMP_ADDR, (int) Math.round(dcCurrentA * 1000)); // FIX3
        writeInputU32(DC_WATT_ADDR, (int) Math.round(dcPowerW)); // FIX0
        writeInputU32(POWER_ADDR, acPowerW);
        writeInputU32(AC_V_A_ADDR, (int) Math.round(acVoltageV * 100)); // FIX2
        writeInputU32(AC_V_B_ADDR, (int) Math.round(acVoltageV * 100));
        writeInputU32(AC_V_C_ADDR, (int) Math.round(acVoltageV * 100));
        writeInputU32(AC_HZ_ADDR, (int) Math.round(acFrequencyHz * 100)); // FIX2
        writeInputU32(HEALTH_ADDR, gridConnected ? HEALTH_OK : HEALTH_FAULT);
        writeInputU32(GRID_CONN_ADDR, gridConnected ? GRID_CONNECTED : GRID_SEPARATED);

        return acPowerW;
    }

    private static void writeInputU32(int offset, int value) {
        ((SimpleInputRegister) spi.getInputRegister(offset)).setValue((value >> 16) & 0xFFFF);
        ((SimpleInputRegister) spi.getInputRegister(offset + 1)).setValue(value & 0xFFFF);
    }

    private static int readInputU32(int offset) {
        int high = spi.getInputRegister(offset).getValue() & 0xFFFF;
        int low = spi.getInputRegister(offset + 1).getValue() & 0xFFFF;
        return (high << 16) | low;
    }

    private static void writeInputU64(int offset, long value) {
        ((SimpleInputRegister) spi.getInputRegister(offset))
                .setValue((int) ((value >> 48) & 0xFFFF));
        ((SimpleInputRegister) spi.getInputRegister(offset + 1))
                .setValue((int) ((value >> 32) & 0xFFFF));
        ((SimpleInputRegister) spi.getInputRegister(offset + 2))
                .setValue((int) ((value >> 16) & 0xFFFF));
        ((SimpleInputRegister) spi.getInputRegister(offset + 3)).setValue((int) (value & 0xFFFF));
    }

    private static int readHoldingU32(int offset) {
        int high = spi.getRegister(offset).getValue() & 0xFFFF;
        int low = spi.getRegister(offset + 1).getValue() & 0xFFFF;
        return (high << 16) | low;
    }

    private static void writeHoldingU32(int offset, int value) {
        spi.getRegister(offset).setValue((value >> 16) & 0xFFFF);
        spi.getRegister(offset + 1).setValue(value & 0xFFFF);
    }
}
