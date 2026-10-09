package io.github.kazumibridge.runtime

import java.io.File

/** Only task-owned MP4 files and UUID-named HLS download directories are cleared. */
object MediaCache {
    private val hlsDirectory = Regex("[a-f0-9]{32}\\.hls")

    fun clear(directory: File) {
        val root = directory.canonicalFile
        root.listFiles()?.forEach { child ->
            if (child.canonicalFile.parentFile == root) {
                if (child.isFile && child.extension == "mp4") child.delete()
                else if (child.isDirectory && hlsDirectory.matches(child.name)) child.deleteRecursively()
            }
        }
    }

    fun usage(directory: File): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)
}
