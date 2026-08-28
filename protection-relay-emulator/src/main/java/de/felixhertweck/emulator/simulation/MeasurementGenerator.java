package de.felixhertweck.emulator.simulation;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.beanit.iec61850bean.Fc;
import de.felixhertweck.emulator.model.Iec61850References;
import de.felixhertweck.emulator.service.ModelNodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MeasurementGenerator implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MeasurementGenerator.class);
    private final ModelNodeWriter writer;
    private final BooleanSupplier breakerClosedSupplier;
    private final Random random = new Random();

    private final long initialDelay;
    private final long period;
    private final TimeUnit timeUnit;

    public MeasurementGenerator(ModelNodeWriter writer, BooleanSupplier breakerClosedSupplier) {
        this(writer, breakerClosedSupplier, 1, 2, TimeUnit.SECONDS);
    }

    public MeasurementGenerator(
            ModelNodeWriter writer,
            BooleanSupplier breakerClosedSupplier,
            long initialDelay,
            long period,
            TimeUnit timeUnit) {
        this.writer = writer;
        this.breakerClosedSupplier = breakerClosedSupplier;
        this.initialDelay = initialDelay;
        this.period = period;
        this.timeUnit = timeUnit;
    }

    public void scheduleOn(ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(this, initialDelay, period, timeUnit);
        logger.info("Dynamic measurement simulation scheduled (every {} {}).", period, timeUnit);
    }

    @Override
    public void run() {
        try {
            boolean breakerClosed = breakerClosedSupplier.getAsBoolean();
            // If breaker is open, current and power should be near zero.
            float currentMultiplier = breakerClosed ? 1.0f : 0.001f;

            // While PTOC has picked up (Str=true), report the overcurrent condition that
            // caused the pickup — instead of a random unrelated fluctuation. This is the single
            // source of truth PTOC's own pickup logic reacts to; no separate fault-state field.
            Boolean ptocPickedUp = writer.readBoolean(Iec61850References.PTOC_STR_GENERAL, Fc.ST);
            float faultMultiplier = Boolean.TRUE.equals(ptocPickedUp) ? 7.0f : 1.0f;

            // Update MMXU Hz
            writer.writeFloat32(
                    Iec61850References.MMXU_HZ_MAG_F, 50.0f + (random.nextFloat() - 0.5f) * 0.2f);

            // Update MMXU TotW
            writer.writeFloat32(
                    Iec61850References.MMXU_TOTW_MAG_F,
                    (1000.0f + (random.nextFloat() - 0.5f) * 50.0f)
                            * currentMultiplier
                            * faultMultiplier);

            // Update MMXU Phase A Current (A.phsA.cVal.mag.f)
            writer.writeFloat32(
                    Iec61850References.MMXU_A_PHSA_MAG_F,
                    (100.0f + (random.nextFloat() - 0.5f) * 5.0f)
                            * currentMultiplier
                            * faultMultiplier);
            writer.writeFloat32(
                    Iec61850References.MMXU_A_PHSB_MAG_F,
                    (99.0f + (random.nextFloat() - 0.5f) * 5.0f)
                            * currentMultiplier
                            * faultMultiplier);
            writer.writeFloat32(
                    Iec61850References.MMXU_A_PHSC_MAG_F,
                    (101.0f + (random.nextFloat() - 0.5f) * 5.0f)
                            * currentMultiplier
                            * faultMultiplier);

            // Update Voltage PPV (Voltage remains even if breaker is open)
            writer.writeFloat32(
                    Iec61850References.MMXU_PPV_PHSAB_MAG_F,
                    400.0f + (random.nextFloat() - 0.5f) * 10.0f);
            writer.writeFloat32(
                    Iec61850References.MMXU_PPV_PHSBC_MAG_F,
                    401.0f + (random.nextFloat() - 0.5f) * 10.0f);
            writer.writeFloat32(
                    Iec61850References.MMXU_PPV_PHSCA_MAG_F,
                    399.0f + (random.nextFloat() - 0.5f) * 10.0f);

        } catch (Exception e) {
            logger.error("Error updating measurements", e);
        }
    }
}
