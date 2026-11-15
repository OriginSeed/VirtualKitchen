package com.processVisualisation.virtualKitchen.common.logging;

import com.mongodb.event.CommandStartedEvent;
import org.bson.BsonDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UsageLedgerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void attributesToTheJobWhenOneIsRunningElseToTheRequest() {
        MDC.put(MdcKeys.REQUEST_ID, "req-a");
        assertThat(UsageLedger.currentKey()).isEqualTo("req:req-a");
        MDC.put(MdcKeys.JOB_ID, "job-a");
        assertThat(UsageLedger.currentKey()).isEqualTo("job-a");
        MDC.clear();
        assertThat(UsageLedger.currentKey()).isNull();
    }

    @Test
    void countsAndDrainsOnce() {
        MDC.put(MdcKeys.JOB_ID, "job-count");
        UsageLedger.increment(UsageLedger.Counter.DB_READS);
        UsageLedger.increment(UsageLedger.Counter.DB_READS);
        UsageLedger.increment(UsageLedger.Counter.DB_WRITES);
        UsageLedger.increment(UsageLedger.Counter.CACHE_HITS);
        UsageLedger.addAi("job-count", 100, 0, 50, 0.002);

        UsageLedger.Usage usage = UsageLedger.drain("job-count");

        assertThat(usage.toLogFields()).isEqualTo("aiRequests=1 inputTokens=100 outputTokens=50 costUsd=0.002000 costInr="
                + String.format("%.4f", 0.002 * UsageLedger.usdToInr())
                + " dbReads=2 dbWrites=1 cacheHits=1 artifactsReused=0");
        assertThat(UsageLedger.drain("job-count")).isEqualTo(UsageLedger.Usage.NONE);
    }

    @Test
    void outsideAnyJobOrRequestNothingIsKept() {
        UsageLedger.increment(UsageLedger.Counter.DB_READS);
        assertThat(UsageLedger.drain(null)).isEqualTo(UsageLedger.Usage.NONE);
    }

    @Test
    void costIsUnpricedWhenNoRequestCouldBePricedAndALowerBoundWhenSomeCould() {
        UsageLedger.addAi("job-unpriced", 1, 0, 1, null);
        assertThat(UsageLedger.drain("job-unpriced").aiLogFields()).contains("costUsd=unpriced costInr=unpriced");

        UsageLedger.addAi("job-partly", 1, 0, 1, null);
        UsageLedger.addAi("job-partly", 1, 0, 1, 0.001);
        assertThat(UsageLedger.drain("job-partly").aiLogFields()).contains("costUsd=>=0.001000");
    }

    @Test
    void evictsOnlyTheOldestEntriesWhenOwnersNeverDrain() {
        UsageLedger.addAi("job-oldest", 1, 0, 1, 0.0);
        for (int i = 0; i < 2_100; i++) {
            UsageLedger.addAi("job-filler-" + i, 1, 0, 1, 0.0);
        }
        UsageLedger.addAi("job-newest", 1, 0, 1, 0.0);

        assertThat(UsageLedger.drain("job-oldest")).isEqualTo(UsageLedger.Usage.NONE);
        assertThat(UsageLedger.drain("job-newest").aiRequests()).isEqualTo(1);
        for (int i = 0; i < 2_100; i++) {
            UsageLedger.drain("job-filler-" + i);
        }
    }

    @Test
    void mongoListenerCountsReadsAndWritesByCommandName() {
        MDC.put(MdcKeys.JOB_ID, "job-mongo");
        MongoUsageConfig.UsageCountingListener listener = new MongoUsageConfig.UsageCountingListener();
        for (String command : new String[]{"find", "aggregate", "insert", "update", "findAndModify", "getMore", "ping"}) {
            CommandStartedEvent event = mock(CommandStartedEvent.class);
            when(event.getCommandName()).thenReturn(command);
            when(event.getCommand()).thenReturn(new BsonDocument());
            listener.commandStarted(event);
        }

        UsageLedger.Usage usage = UsageLedger.drain("job-mongo");

        assertThat(usage.dbReads()).isEqualTo(2);
        assertThat(usage.dbWrites()).isEqualTo(3);
    }

    @Test
    void logScopeRestoresOuterValuesAndNests() {
        MDC.put(MdcKeys.JOB_ID, "outer");
        try (LogScope ignored = LogScope.with(MdcKeys.JOB_ID, "inner", MdcKeys.AREA, "system")) {
            assertThat(MDC.get(MdcKeys.JOB_ID)).isEqualTo("inner");
            assertThat(MDC.get(MdcKeys.AREA)).isEqualTo("system");
            try (LogScope cleared = LogScope.with(MdcKeys.AREA, null)) {
                assertThat(MDC.get(MdcKeys.AREA)).isNull();
            }
            assertThat(MDC.get(MdcKeys.AREA)).isEqualTo("system");
        }
        assertThat(MDC.get(MdcKeys.JOB_ID)).isEqualTo("outer");
        assertThat(MDC.get(MdcKeys.AREA)).isNull();
    }
}
