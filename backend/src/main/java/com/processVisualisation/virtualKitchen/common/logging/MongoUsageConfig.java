package com.processVisualisation.virtualKitchen.common.logging;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Counts MongoDB reads and writes into {@link UsageLedger}, so an action's closing log line says how much database
 * work it did ({@code dbReads=.. dbWrites=..}). The synchronous driver notifies on the calling thread, so the
 * count lands on the job or request that issued the command. Only the command name is looked at, never its body.
 */
@Configuration
public class MongoUsageConfig {

    static final Set<String> READS = Set.of("find", "aggregate", "count", "countDocuments", "distinct");
    static final Set<String> WRITES = Set.of("insert", "update", "delete", "findAndModify");

    @Bean
    MongoClientSettingsBuilderCustomizer usageCountingCommandListener() {
        return builder -> builder.addCommandListener(new UsageCountingListener());
    }

    static final class UsageCountingListener implements CommandListener {
        @Override
        public void commandStarted(CommandStartedEvent event) {
            String command = event.getCommandName();
            if (READS.contains(command)) {
                UsageLedger.increment(UsageLedger.Counter.DB_READS);
            } else if (WRITES.contains(command)) {
                UsageLedger.increment(UsageLedger.Counter.DB_WRITES);
            }
        }
    }
}
