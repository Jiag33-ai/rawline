package app.rawline.feature.settings

/** One backup in the restore list ([line] is already worded: "4 Oct, 3.2 MB, 312 edits"). */
data class BackupItem(val name: String, val line: String)

/** The question shown before anything is restored. [canRestore] is false for a backup that failed its check. */
data class RestoreOffer(val text: String, val canRestore: Boolean)

/** Everything the Backups section of Settings shows, worded by the caller (the words are tested in core/data). */
data class BackupUiState(
    /** "Documents/Rawline/backups", "your backup folder" or the weak MediaStore note. */
    val where: String = "",
    /** "Last backup: today 14:15, 3.2 MB" or "No backup yet". */
    val last: String = "No backup yet",
    /** Where that last backup was put, or null before the first. */
    val lastWhere: String? = null,
    val error: String? = null,
    val auto: Boolean = true,
    /** Offered when the backups would otherwise sit in the weak place (All files access is off). */
    val canChooseFolder: Boolean = false,
    val running: Boolean = false,
    /** Null until "Restore from a backup..." is tapped; then the backups in the target, newest first. */
    val list: List<BackupItem>? = null,
    val offer: RestoreOffer? = null,
)

/** The actions of the Backups section. */
class BackupActions(
    val onBackupNow: () -> Unit = {},
    val onSaveCopy: () -> Unit = {},
    val onAutoChange: (Boolean) -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onOpenRestore: () -> Unit = {},
    val onCloseRestore: () -> Unit = {},
    val onPickBackup: (String) -> Unit = {},
    val onChooseFile: () -> Unit = {},
    val onConfirmRestore: () -> Unit = {},
    val onCancelRestore: () -> Unit = {},
)
