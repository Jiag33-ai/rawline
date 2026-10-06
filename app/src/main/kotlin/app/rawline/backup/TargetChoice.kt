package app.rawline.backup

/** Where automatic backups go, strongest first (W06 D1). */
enum class TargetKind { FILES, FOLDER, MEDIASTORE }

/** The order of D1 as a pure function, so it is tested on the host. */
object TargetChoice {
    /** [allFilesAccess]: the app may write plain files under Documents. [folderUsable]: a chosen folder whose permission is still held. */
    fun pick(allFilesAccess: Boolean, folderUsable: Boolean): TargetKind = when {
        allFilesAccess -> TargetKind.FILES
        folderUsable -> TargetKind.FOLDER
        else -> TargetKind.MEDIASTORE
    }

    /** The words Settings shows after "Backing up to". */
    fun label(k: TargetKind): String = when (k) {
        TargetKind.FILES -> "Documents/Rawline/backups"
        TargetKind.FOLDER -> "your backup folder"
        TargetKind.MEDIASTORE -> "this phone (Documents/Rawline/backups). Backups here may not be found after a reinstall"
    }

    /** True where a reinstall may not find the files again: Settings then offers a folder. */
    fun isWeak(k: TargetKind) = k == TargetKind.MEDIASTORE
}
