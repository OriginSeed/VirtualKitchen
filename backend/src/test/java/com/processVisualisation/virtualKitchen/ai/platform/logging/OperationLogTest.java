package com.processVisualisation.virtualKitchen.ai.platform.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiCostLedger;
import com.processVisualisation.virtualKitchen.ai.platform.usage.AiUsageSummary;
import com.processVisualisation.virtualKitchen.ai.provider.exception.AiCommunicationException;
import com.processVisualisation.virtualKitchen.ai.recipe.RecipeProcessAiException;
import com.processVisualisation.virtualKitchen.common.logging.FailureLogger;
import com.processVisualisation.virtualKitchen.common.logging.LogCapture;
import com.processVisualisation.virtualKitchen.common.logging.MdcKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogTest {

    private static final Logger log = LoggerFactory.getLogger(OperationLogTest.class);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void writesAStartedAndACompletedLineWithDurationAndTheOperationsAiSpend() {
        try (LogCapture logs = LogCapture.of(OperationLogTest.class)) {
            OperationLog operation = OperationLog.start(log, "demo_job", "job-1", "recipeId=7 steps=3");
            assertThat(MDC.get(MdcKeys.JOB_ID)).as("lines and AI requests of the operation carry its id").isEqualTo("job-1");

            AiCostLedger.record("job-1", new AiUsageSummary(1, 100, 0, 1290, 0, 1390, 0.03873));
            operation.completed("status=COMPLETED");

            ILoggingEvent started = logs.events("demo_job_started").get(0);
            assertThat(started.getLevel()).isEqualTo(Level.INFO);
            assertThat(started.getFormattedMessage()).contains("recipeId=7 steps=3");

            ILoggingEvent completed = logs.events("demo_job_completed").get(0);
            assertThat(completed.getLevel()).isEqualTo(Level.INFO);
            assertThat(completed.getFormattedMessage())
                    .contains("durationMs=")
                    .contains("status=COMPLETED")
                    .contains("aiRequests=1 inputTokens=100 outputTokens=1290 costUsd=0.038730 costInr=");
        }
        assertThat(AiCostLedger.drain("job-1")).as("totals are drained once").isEqualTo(AiCostLedger.Totals.NONE);
    }

    @Test
    void partialFailureIsAWarning() {
        try (LogCapture logs = LogCapture.of(OperationLogTest.class)) {
            OperationLog.start(log, "demo_job", "job-2", "").completedWithErrors("failedSteps=1");

            assertThat(logs.events("demo_job_completed").get(0).getLevel()).isEqualTo(Level.WARN);
        }
    }

    @Test
    void failureStatesTheReasonAtTheLevelItDeserves() {
        try (LogCapture logs = LogCapture.of(OperationLogTest.class)) {
            OperationLog.start(log, "demo_job", "job-3", "").failed(new AiCommunicationException("Gemini 429"), "recipeId=7");
            OperationLog.start(log, "demo_job", "job-4", "").failed(new RecipeProcessAiException("invalid flow"), "");
            OperationLog.start(log, "demo_job", "job-5", "").failed(new IllegalStateException("bug"), "");

            var failed = logs.events("demo_job_failed");
            assertThat(failed).hasSize(3);
            assertThat(failed.get(0).getLevel()).isEqualTo(Level.ERROR);
            assertThat(failed.get(0).getThrowableProxy()).as("known external failure: reason, no stack").isNull();
            assertThat(failed.get(0).getFormattedMessage())
                    .contains("durationMs=").contains("recipeId=7").contains("error=\"Gemini 429\"").contains("aiRequests=0");
            assertThat(failed.get(1).getLevel()).isEqualTo(Level.WARN);
            assertThat(failed.get(2).getLevel()).isEqualTo(Level.ERROR);
            assertThat(failed.get(2).getThrowableProxy()).as("a bug keeps its stack trace").isNotNull();
        }
    }

    @Test
    void aJobStartedInsideAnotherRecordsItsParentAndCloseRestoresTheOuterJob() {
        try (OperationLog outer = OperationLog.start(log, "outer_job", "job-outer", "")) {
            try (OperationLog inner = OperationLog.start(log, "inner_job", "job-inner", "")) {
                assertThat(MDC.get(MdcKeys.JOB_ID)).isEqualTo("job-inner");
                assertThat(MDC.get(MdcKeys.PARENT_JOB_ID)).isEqualTo("job-outer");
            }
            assertThat(MDC.get(MdcKeys.JOB_ID)).isEqualTo("job-outer");
            assertThat(MDC.get(MdcKeys.PARENT_JOB_ID)).isNull();
        }
        assertThat(MDC.get(MdcKeys.JOB_ID)).as("no job id leaks past the operation").isNull();
    }

    @Test
    void theClosingLineSaysWhatTheOperationUsedBesidesAi() {
        try (LogCapture logs = LogCapture.of(OperationLogTest.class);
             OperationLog operation = OperationLog.start(log, "demo_job", "job-usage", "")) {
            com.processVisualisation.virtualKitchen.common.logging.UsageLedger.increment(
                    com.processVisualisation.virtualKitchen.common.logging.UsageLedger.Counter.DB_WRITES);
            operation.completed("");

            assertThat(logs.events("demo_job_completed").get(0).getFormattedMessage())
                    .contains("aiRequests=0").contains("dbReads=0 dbWrites=1");
        }
    }

    @Test
    void oneLineFlattensAndBoundsProviderMessages() {
        assertThat(FailureLogger.oneLine("line one\n  \"quoted\"\tline two")).isEqualTo("line one 'quoted' line two");
        assertThat(FailureLogger.oneLine("x".repeat(500))).hasSize(303).endsWith("...");
        assertThat(FailureLogger.oneLine(null)).isEmpty();
    }
}
