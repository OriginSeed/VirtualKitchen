package com.processVisualisation.virtualKitchen.kitchen.repository;

import com.processVisualisation.virtualKitchen.kitchen.model.Inventory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Ensures at most one inventory row per kitchen and item, so two concurrent "add" requests for the same item merge
 * (see {@code InventoryService#addOrUpdate}) instead of creating two rows.
 * <p>
 * Created here, not with an annotation, so a database that already holds duplicate rows (possible before this
 * index existed) still starts: the build then fails, which is logged with what to do, and lookups keep working
 * because they read the oldest matching row.
 */
@Component
public class InventoryIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(InventoryIndexInitializer.class);

    static final String INDEX_NAME = "inventory_kitchen_item_unique";

    private final MongoTemplate mongoTemplate;

    public InventoryIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureUniqueKitchenItemIndex() {
        Index index = new Index()
                .on("kitchenId", Sort.Direction.ASC)
                .on("itemType", Sort.Direction.ASC)
                .on("itemId", Sort.Direction.ASC)
                .unique()
                .partial(PartialIndexFilter.of(where("kitchenId").exists(true)))
                .named(INDEX_NAME);
        try {
            mongoTemplate.indexOps(Inventory.class).createIndex(index);
        } catch (RuntimeException e) {
            log.error("event=inventory_unique_index_failed index={} reason=\"{}\" action=\"merge duplicate inventory rows "
                    + "(same kitchenId, itemType, itemId) and restart\"", INDEX_NAME, e.getMessage());
        }
    }
}
