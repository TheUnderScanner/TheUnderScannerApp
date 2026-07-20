package com.underscanner.theunderscannerapp

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Single source of truth for where downloaded scan artifacts live on the phone.
 *
 * "Downloaded" is defined by the file existing here — there is no server flag.
 * The OpenGL viewer also reads `.pcd` files from this same directory.
 *
 * Files live in the **public** `Documents/UnderScanner/Scans` folder (visible in the Files app
 * and over USB) rather than app-private storage, so scans are easy to find and copy off the
 * phone. Writing there requires All-files access (see [MainActivity] permission prompt).
 */
object LocalScanStorage {

    /** Public sub-path under Documents where scans are stored. */
    private const val PUBLIC_SUBDIR = "UnderScanner/Scans"

    /** Public directory (Internal storage/Documents/UnderScanner/Scans) holding downloaded `.pcd` files. */
    fun scansDir(context: Context): File {
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(documents, PUBLIC_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Legacy app-private directory used before scans moved to public Documents (for migration). */
    private fun legacyScansDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Scans")

    /**
     * One-time move of any scans left in the old app-private folder into the public one.
     * Safe to call repeatedly; only runs work while the legacy folder still holds files.
     * Requires All-files access to be granted (so the public dir is writable) — call after grant.
     */
    fun migrateLegacyScans(context: Context) {
        val legacy = legacyScansDir(context)
        val files = legacy.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) {
            legacy.delete() // prune the now-empty legacy dir
            return
        }
        val target = scansDir(context)
        files.forEach { src ->
            val dst = File(target, src.name)
            if (dst.exists()) {
                src.delete() // already migrated
            } else {
                runCatching { src.copyTo(dst, overwrite = false) }
                    .onSuccess { src.delete() }
            }
        }
        legacy.delete() // remove the legacy dir once emptied (no-op if copies failed)
    }

    /** Local file a scan's point cloud is (or would be) stored at. */
    fun pcdFile(context: Context, scanName: String): File =
        File(scansDir(context), "$scanName.pcd")

    /** Local sidecar file a scan's LiDAR path (`.traj`) is (or would be) stored at. */
    fun trajFile(context: Context, scanName: String): File =
        File(scansDir(context), "$scanName.traj")

    /** Local sidecar file a scan's health log (`.jsonl`) is (or would be) stored at. */
    fun healthFile(context: Context, scanName: String): File =
        File(scansDir(context), "$scanName.jsonl")

    /** Whether this scan's health log has been downloaded to the phone. */
    fun hasHealthLog(context: Context, scanName: String): Boolean =
        healthFile(context, scanName).let { it.exists() && it.length() > 0 }

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
