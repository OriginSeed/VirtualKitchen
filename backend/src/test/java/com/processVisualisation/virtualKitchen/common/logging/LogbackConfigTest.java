package com.processVisualisation.virtualKitchen.common.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loads the real {@code logback-spring.xml} through Spring Boot's logging system (so {@code <springProfile>} and
 * {@code <springProperty>} work) and checks what reaches the console and the files.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogbackConfigTest {

    private static final String APP_LOGGER = "com.processVisualisation.virtualKitchen.LogbackConfigProbe";

    @TempDir
    Path logDir;

    private LoggingSystem loggingSystem;

    @AfterEach
    void restoreDefaultLogging() {
        MDC.clear();
        if (loggingSystem != null) {
            // Put the plain test-JVM configuration back so later tests are unaffected.
            loggingSystem.cleanUp();
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            ch.qos.logback.classic.BasicConfigurator basic = new ch.qos.logback.classic.BasicConfigurator();
            basic.setContext(context);
            basic.configure(context);
        }
    }

    @Test
    void devProfileKeepsApplicationLogAtInfoAndDetailInDebugLog(CapturedOutput output) throws Exception {
        initialize();
        org.slf4j.Logger log = LoggerFactory.getLogger(APP_LOGGER);

        MDC.put(MdcKeys.REQUEST_ID, "8f32ab91c0de4a17");
        MDC.put(MdcKeys.JOB_ID, "job-123");
        MDC.put(MdcKeys.AREA, "recipe");
        MDC.put(MdcKeys.ACTION, "recipe.update");
        MDC.put(MdcKeys.WORKFLOW_ID, "wf-9");
        log.debug("event=probe_debug_only");
        log.info("event=probe_info");
        log.error("event=probe_error", new IllegalStateException("probe stack"));
        MDC.clear();
        log.info("event=probe_without_context");

        assertThat(output.getOut())
                .contains("event=probe_info")
                .contains("[8f32ab91c0de4a17 recipe.update]")
                .doesNotContain("event=probe_debug_only");

        String file = read("application.log");
        assertThat(file)
                .contains("event=probe_info")
                .contains("requestId=8f32ab91c0de4a17 userId=- area=recipe action=recipe.update jobId=job-123 workflowId=wf-9]")
                .as("unset optional keys are left out, unset fixed keys print -")
                .contains("[requestId=- userId=- area=- action=- jobId=-] ")
                .contains("java.lang.IllegalStateException: probe stack")
                .as("DEBUG detail goes to debug.log only").doesNotContain("event=probe_debug_only");

        assertThat(read("debug.log"))
                .contains("event=probe_debug_only")
                .contains("DEBUG [main]")
                .contains("event=probe_info");

        String errors = read("application-error.log");
        assertThat(errors).contains("event=probe_error").doesNotContain("event=probe_info");

        String json = read("application.json.log");
        String infoLine = json.lines().filter(l -> l.contains("event=probe_info")).findFirst().orElseThrow();
        assertThat(infoLine).startsWith("{").endsWith("}")
                .contains("\"action\":\"recipe.update\"")
                .contains("\"jobId\":\"job-123\"");
        assertThat(json).as("JSON file is INFO and above too").doesNotContain("event=probe_debug_only");
    }

    @Test
    void prodProfileKeepsDebugOutOfTheFile() throws Exception {
        initialize("prod");
        org.slf4j.Logger log = LoggerFactory.getLogger(APP_LOGGER);

        log.debug("event=probe_debug_only");
        log.info("event=probe_info");

        assertThat(read("application.log")).contains("event=probe_info").doesNotContain("event=probe_debug_only");
    }

    @Test
    void accessLinesGoToAccessLogOnly() throws Exception {
        initialize();

        LoggerFactory.getLogger(RequestIdFilter.ACCESS_LOGGER).info("method=GET path=/api/probe status=200 durationMs=1");

        assertThat(read("access.log")).contains("path=/api/probe");
        assertThat(read("application.log")).doesNotContain("path=/api/probe");
        assertThat(read("debug.log")).doesNotContain("path=/api/probe");
    }

    @Test
    void mongoDriverChatterStaysOutOfTheApplicationLog() throws Exception {
        initialize();

        LoggerFactory.getLogger("org.mongodb.driver.cluster").info("Exception in monitor thread while connecting to server");

        assertThat(read("debug.log")).contains("Exception in monitor thread");
        assertThat(read("application.log")).doesNotContain("Exception in monitor thread");
    }

    @Test
    void aiPayloadLoggerIsOffByDefault() throws Exception {
        initialize();

        LoggerFactory.getLogger("virtualKitchen.ai.payload").debug("event=payload_probe");

        assertThat(LoggerFactory.getLogger("virtualKitchen.ai.payload").isDebugEnabled()).isFalse();
        assertThat(read("application.log")).doesNotContain("event=payload_probe");
    }

    @Test
    void rollingPolicyRotatesBySizeAndTimeWithBoundedRetention() {
        initialize();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        RollingFileAppender<?> file = (RollingFileAppender<?>) root.getAppender("FILE");
        SizeAndTimeBasedRollingPolicy<?> policy = (SizeAndTimeBasedRollingPolicy<?>) file.getRollingPolicy();

        assertThat(file.getFile()).endsWith("application.log");
        assertThat(policy.getFileNamePattern()).endsWith("application.%d{yyyy-MM-dd}.%i.log.gz");
        assertThat(policy.getMaxHistory()).isEqualTo(14);
        assertThat(policy.isCleanHistoryOnStart()).isTrue();
        assertThat(maxFileSizeOf(policy)).isEqualTo(20L * 1024 * 1024);
    }

    /** Logback exposes no getter for the size threshold. */
    private static long maxFileSizeOf(SizeAndTimeBasedRollingPolicy<?> policy) {
        try {
            java.lang.reflect.Field field = SizeAndTimeBasedRollingPolicy.class.getDeclaredField("maxFileSize");
            field.setAccessible(true);
            return ((ch.qos.logback.core.util.FileSize) field.get(policy)).getSize();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void initialize(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("logging.file.path", logDir.toString());
        environment.setActiveProfiles(profiles);
        loggingSystem = LoggingSystem.get(getClass().getClassLoader());
        // A Spring test context that ran earlier in this JVM marks logback as already initialized,
        // which would make initialize() below a silent no-op; start from a clean slate.
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml",
                LogFile.get(environment));
    }

    private String read(String fileName) throws Exception {
        return Files.readString(logDir.resolve(fileName), StandardCharsets.UTF_8);
    }
}
