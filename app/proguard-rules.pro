# Pinot Rouge — release keep rules. Every rule here exists because of a
# concrete reflective path; do not add "just in case" keeps.

# Audit, Wave 16 phase 3:
# - No Class.forName / loadClass anywhere in app/ or core/.
# - MmsPduDecoder is a plain object called from WapPushDeliverReceiver and
#   MmsDownloadReceiver. It is not reflective, so it needs no keep.
# - Hilt, Room, WorkManager and Compose ship consumer rules; the keeps below
#   cover the app types those libraries look up by name or constructor.

# Room instantiates @Entity classes, the RoomDatabase subclass, DAOs and
# TypeConverters by reflection. Without this, a minified release cannot open
# pinot_rouge.db or round-trip Condition/Action through Converters.
-keep class com.pinotrouge.messaging.data.room.** { *; }

# Enums stored via Enum.name / Enum.valueOf in Converters. Default Android
# keepclassmembers on enums usually covers this; pin it so a rule encoding
# cannot become unreadable after obfuscation.
-keepclassmembers enum com.pinotrouge.messaging.rules.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# WorkManager looks up Worker implementations by class name from the work
# spec. Hilt's worker factory then injects them. Consumer rules keep the
# generic Worker types; these keep our three @HiltWorker classes.
-keep class com.pinotrouge.messaging.work.QuarantineCommitWorker { *; }
-keep class com.pinotrouge.messaging.work.WeeklyDigestWorker { *; }
-keep class com.pinotrouge.messaging.notify.OtpClipboardClearWorker { *; }
