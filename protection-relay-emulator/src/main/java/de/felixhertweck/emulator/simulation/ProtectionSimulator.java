package de.felixhertweck.emulator.simulation;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.beanit.iec61850bean.Fc;
import de.felixhertweck.emulator.model.Iec61850References;
import de.felixhertweck.emulator.service.ModelNodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProtectionSimulator implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ProtectionSimulator.class);
    private final ModelNodeWriter writer;
    private final Runnable onTrip;
    private final Random random = new Random();

    private final long initialDelay;
    private final long period;
    private final TimeUnit timeUnit;
    private final double faultProbability;
    private final long operateDelaySeconds;
    private final long clearDelaySeconds;

    private ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pendingOperate;
    private volatile ScheduledFuture<?> pendingClear;

    // Serialises the fault tasks against cancelPending(): a task that has already started keeps
    // the lock until it is done, and one cancelled before it started sees a bumped generation.
    private final Object faultLock = new Object();
    private long faultGeneration;

    /**
     * @param onTrip invoked when PTOC operates (after {@code operateDelaySeconds}) — opens the
     *     breaker directly, bypassing the CSWI1 Select/Operate gate used by external MMS clients.
     */
    public ProtectionSimulator(ModelNodeWriter writer, Runnable onTrip) {
        // Default: ~15% chance every 120s (avg. one fault every ~13 min) — rare enough that a
        // typical eval run isn't confounded by an unrelated auto-trip racing the agent's own
        // Goal C attempt, now that PTOC operate has a real effect on the breaker.
        this(writer, onTrip, 60, 120, TimeUnit.SECONDS, 0.15, 1, 4);
    }

    public ProtectionSimulator(
            ModelNodeWriter writer,
            Runnable onTrip,
            long initialDelay,
            long period,
            TimeUnit timeUnit,
            double faultProbability,
            long operateDelaySeconds,
            long clearDelaySeconds) {
        this.writer = writer;
        this.onTrip = onTrip;
        this.initialDelay = initialDelay;
        this.period = period;
        this.timeUnit = timeUnit;
        this.faultProbability = faultProbability;
        this.operateDelaySeconds = operateDelaySeconds;
        this.clearDelaySeconds = clearDelaySeconds;
    }

    public void scheduleOn(ScheduledExecutorService scheduler) {
        this.scheduler = scheduler;
        scheduler.scheduleAtFixedRate(this, initialDelay, period, timeUnit);
        logger.info("Protection fault simulation scheduled (every {} {}).", period, timeUnit);
    }

    @Override
    public void run() {
        try {
            if (random.nextDouble() < faultProbability) {
                simulateFault();
            }
        } catch (Exception e) {
            logger.error("Error in protection fault checker", e);
        }
    }

    /** Simulates a fault event (pickup -> trip after delay -> clear indicators after delay). */
    public void simulateFault() {
        if (scheduler == null) {
            logger.warn("Scheduler not set, cannot run asynchronous fault simulation steps");
            return;
        }

        logger.info("Simulating protection fault: PTOC pickup (Str=true)");
        writer.writeBoolean(Iec61850References.PTOC_STR_GENERAL, Fc.ST, true);

        final long generation;
        synchronized (faultLock) {
            generation = faultGeneration;
        }

        pendingOperate =
                scheduler.schedule(
                        () -> {
                            synchronized (faultLock) {
                                if (faultGeneration != generation) {
                                    return;
                                }
                                logger.info("PTOC operated (Op=true) — tripping breaker");
                                writer.writeBoolean(
                                        Iec61850References.PTOC_OP_GENERAL, Fc.ST, true);
                                onTrip.run();
                            }
                        },
                        operateDelaySeconds,
                        TimeUnit.SECONDS);

        pendingClear =
                scheduler.schedule(
                        () -> {
                            synchronized (faultLock) {
                                if (faultGeneration != generation) {
                                    return;
                                }
                                logger.info("Fault cleared: resetting PTOC indicators");
                                writer.writeBoolean(
                                        Iec61850References.PTOC_STR_GENERAL, Fc.ST, false);
                                writer.writeBoolean(
                                        Iec61850References.PTOC_OP_GENERAL, Fc.ST, false);
                            }
                        },
                        clearDelaySeconds,
                        TimeUnit.SECONDS);
    }

    /**
     * Cancels any operate/clear tasks from a fault cycle still in progress. Blocks until a task
     * that is already running has finished, so the caller can safely overwrite the state it wrote.
     */
    public void cancelPending() {
        synchronized (faultLock) {
            faultGeneration++;
            if (pendingOperate != null) {
                pendingOperate.cancel(false);
            }
            if (pendingClear != null) {
                pendingClear.cancel(false);
            }
        }
    }
}
