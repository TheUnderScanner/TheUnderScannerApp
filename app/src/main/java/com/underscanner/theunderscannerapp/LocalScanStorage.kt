package com.underscanner.theunderscannerapp

import android.content.Context
import java.io.File

/**
 * Single source of truth for where downloaded scan artifacts live on the phone.
 *
 * "Downloaded" is defined by the file existing here — there is no server flag.
 * The OpenGL viewer also reads `.pcd` files from this same directory.
 */
object LocalScanStorage {

    /** App-scoped directory holding downloaded `.pcd` files. */
    fun scansDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "Scans")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Local file a scan's point cloud is (or would be) stored at. */
    fun pcdFile(context: Context, scanName: String): File =
        File(scansDir(context), "$scanName.pcd")

    /** Local sidecar file a scan's LiDAR path (`.traj`) is (or would be) stored at. */
    fun trajFile(context: Context, scanName: String): File =
        File(scansDir(context), "$scanName.traj")

    /** Whether the scan's `.pcd` has been downloaded to this phone. */
    fun isDownloaded(context: Context, scanName: String): Boolean =
        pcdFile(context, scanName).let { it.exists() && it.length() > 0 }

    /** Names of scans (without extension) whose `.pcd` exists locally. */
    fun downloadedScanNames(context: Context): List<String> =
        scansDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".pcd") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".pcd") }
            ?: emptyList()

    /** File used to cache the last successful `/scans` response for offline rendering. */
    fun scansCacheFile(context: Context): File =
        File(context.filesDir, "scans_cache.json")
}
