package com.linguakey.keyboard.latin.shortcuts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Core data models for LinguaKey Personal Dictionaries & Text Shortcuts (Schema v1).
 */
public final class LinguaKeyShortcutModels {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String DEFAULT_GROUP_NAME = "عام";
    public static final String LANG_ALL = "all";

    public enum DuplicateStrategy {
        SKIP,
        OVERWRITE,
        KEEP_BOTH
    }

    public static final class ShortcutEntry {
        public String id;
        public String trigger;
        public String replacement;
        public String groupName;
        public String language;
        public boolean enabled;
        public long createdAt;
        public long updatedAt;

        public ShortcutEntry() {
            this.id = UUID.randomUUID().toString();
            this.trigger = "";
            this.replacement = "";
            this.groupName = DEFAULT_GROUP_NAME;
            this.language = LANG_ALL;
            this.enabled = true;
            long now = System.currentTimeMillis();
            this.createdAt = now;
            this.updatedAt = now;
        }

        public ShortcutEntry(
                final String id,
                final String trigger,
                final String replacement,
                final String groupName,
                final String language,
                final boolean enabled,
                final long createdAt,
                final long updatedAt
        ) {
            this.id = (id != null && !id.trim().isEmpty()) ? id.trim() : UUID.randomUUID().toString();
            this.trigger = trigger != null ? trigger.trim() : "";
            this.replacement = replacement != null ? replacement : "";
            this.groupName = (groupName != null && !groupName.trim().isEmpty())
                    ? groupName.trim()
                    : DEFAULT_GROUP_NAME;
            this.language = (language != null && !language.trim().isEmpty())
                    ? language.trim().toLowerCase(Locale.US)
                    : LANG_ALL;
            this.enabled = enabled;
            long now = System.currentTimeMillis();
            this.createdAt = createdAt > 0L ? createdAt : now;
            this.updatedAt = updatedAt > 0L ? updatedAt : now;
        }

        public ShortcutEntry copy() {
            return new ShortcutEntry(id, trigger, replacement, groupName, language, enabled, createdAt, updatedAt);
        }

        public String normalizedTrigger() {
            return normalizeTrigger(trigger);
        }
    }

    public static final class DictionaryGroup {
        public final String name;
        public final List<ShortcutEntry> shortcuts;

        public DictionaryGroup(final String name, final List<ShortcutEntry> shortcuts) {
            this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : DEFAULT_GROUP_NAME;
            this.shortcuts = shortcuts != null ? new ArrayList<>(shortcuts) : new ArrayList<ShortcutEntry>();
        }
    }

    public static final class ImportPreviewResult {
        public int schemaVersion = CURRENT_SCHEMA_VERSION;
        public final List<ShortcutEntry> validNewEntries = new ArrayList<>();
        public final List<ShortcutEntry> duplicateEntries = new ArrayList<>();
        public final List<String> parsedGroupNames = new ArrayList<>();
        public final List<String> errorMessages = new ArrayList<>();
        public int invalidCount = 0;
        public String fatalError = null;

        public boolean hasFatalError() {
            return fatalError != null && !fatalError.isEmpty();
        }

        public int getTotalValidCount() {
            return validNewEntries.size() + duplicateEntries.size();
        }
    }

    public static final class ImportReport {
        public int addedCount = 0;
        public int updatedCount = 0;
        public int skippedCount = 0;
        public int errorCount = 0;
        public boolean success = true;
        public String errorMessage = null;
    }

    public static String normalizeTrigger(final String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private LinguaKeyShortcutModels() {
    }
}
