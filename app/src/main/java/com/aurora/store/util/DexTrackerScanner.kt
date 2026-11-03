/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Finds Exodus trackers in an APK without any network, by matching the trackers'
 * code signatures against the class names declared in the APK's `classes*.dex`
 * files. The signature database itself comes from Exodus's public tracker list
 * (no token required); only the per-app *report* endpoint needs one.
 *
 * Matching is prefix-with-boundary on normalized class names (`/` and `.` are
 * equivalent, so `com.foo.Bar` matches `Lcom/foo/Bar;` but `com.foo.Barista`
 * does not match signature `com.foo.Bar`), which mirrors how the server-side
 * analyzer treats package-prefix signatures while avoiding substring false
 * positives.
 *
 * Pure JVM logic with no Android dependencies, so it is covered by unit tests.
 * Everything is best-effort: corrupt/truncated files yield fewer (or zero)
 * matches, never an exception.
 */
object DexTrackerScanner {

    private val CLASSES_DEX = Regex("^classes\\d*\\.dex$")

    /** Skip absurdly large DEX files rather than risking an OOM on a broken zip. */
    private const val MAX_DEX_BYTES = 300L * 1024 * 1024

    /**
     * @param apkFile an APK (installed copy or downloaded file) to scan.
     * @param signatures tracker id to code signature, e.g. from the synced tracker DB.
     * @return ids of trackers whose signature matched a class in the APK.
     */
    fun scanApk(apkFile: File, signatures: Map<Int, String>): Set<Int> {
        if (signatures.isEmpty() || !apkFile.isFile) return emptySet()
        val trie = PrefixTrie(signatures)
        if (trie.isEmpty()) return emptySet()
        val matched = mutableSetOf<Int>()
        try {
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && CLASSES_DEX.matches(it.name) }
                    .filter { it.size < 0 || it.size <= MAX_DEX_BYTES }
                    .forEach { entry ->
                        zip.getInputStream(entry).use { it.readBytes() }.let { dex ->
                            matchClassNames(dex, trie, matched)
                            if (matched.size >= signatures.size) return matched
                        }
                    }
            }
        } catch (_: Exception) {
            // Corrupt zip / unreadable entry: keep whatever matched so far.
        }
        return matched
    }

    private fun matchClassNames(dex: ByteArray, trie: PrefixTrie, matched: MutableSet<Int>) {
        try {
            if (dex.size < Header.SIZE || dex[0] != 'd'.code.toByte() ||
                dex[1] != 'e'.code.toByte() || dex[2] != 'x'.code.toByte()
            ) {
                return
            }
            val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
            val stringIdsSize = buffer.getInt(Header.STRING_IDS_SIZE_OFF)
            val stringIdsOff = buffer.getInt(Header.STRING_IDS_OFF_OFF)
            val typeIdsSize = buffer.getInt(Header.TYPE_IDS_SIZE_OFF)
            val typeIdsOff = buffer.getInt(Header.TYPE_IDS_OFF_OFF)
            if (stringIdsSize < 0 || typeIdsSize < 0 || stringIdsSize > dex.size ||
                typeIdsSize > dex.size
            ) {
                return
            }
            repeat(typeIdsSize) { index ->
                val typeEntry = typeIdsOff + index * Int.SIZE_BYTES
                if (typeEntry < 0 || typeEntry + Int.SIZE_BYTES > dex.size) return
                val descriptorIdx = buffer.getInt(typeEntry)
                if (descriptorIdx < 0 || descriptorIdx >= stringIdsSize) return@repeat
                val stringEntry = stringIdsOff + descriptorIdx * Int.SIZE_BYTES
                if (stringEntry < 0 || stringEntry + Int.SIZE_BYTES > dex.size) return@repeat
                val dataOff = buffer.getInt(stringEntry)
                val descriptor = readMutf8String(dex, dataOff) ?: return@repeat
                // "Lcom/foo/Bar;" -> "com.foo.Bar" for boundary matching.
                val className = descriptor.trimStart('L').trimEnd(';').replace('/', '.')
                trie.match(className, matched)
            }
        } catch (_: Exception) {
            // Truncated/corrupt DEX: keep whatever matched so far.
        }
    }

    /**
     * Reads a `string_data_item` at [offset]: uleb128 length followed by MUTF-8
     * bytes terminated by 0x00. Returns null when the offset/length runs past the
     * buffer instead of throwing.
     */
    private fun readMutf8String(dex: ByteArray, offset: Int): String? {
        if (offset < 0 || offset >= dex.size) return null
        var pos = offset
        // Skip the uleb128 utf16-length prefix.
        var shift = 0
        while (true) {
            if (pos >= dex.size) return null
            val byte = dex[pos++].toInt() and 0xFF
            if (byte and 0x80 == 0) break
            shift += 7
            if (shift > 28) return null
        }
        val out = StringBuilder()
        while (true) {
            if (pos >= dex.size) return null
            val b0 = dex[pos++].toInt() and 0xFF
            when {
                b0 == 0 -> break
                b0 and 0x80 == 0 -> out.append(b0.toChar())
                b0 and 0xE0 == 0xC0 -> {
                    if (pos >= dex.size) return null
                    val b1 = dex[pos++].toInt() and 0xFF
                    if (b1 and 0xC0 != 0x80) return null
                    out.append(((b0 and 0x1F) shl 6 or (b1 and 0x3F)).toChar())
                }

                b0 and 0xF0 == 0xE0 -> {
                    if (pos + 1 >= dex.size) return null
                    val b1 = dex[pos++].toInt() and 0xFF
                    val b2 = dex[pos++].toInt() and 0xFF
                    if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80) return null
                    out.append(
                        ((b0 and 0x0F) shl 12 or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)).toChar()
                    )
                }

                else -> return null
            }
        }
        return out.toString()
    }

    /** Prefix trie over normalized signatures; a terminal only fires on a boundary. */
    private class PrefixTrie(signatures: Map<Int, String>) {

        private class Node {
            val children = HashMap<Char, Node>()
            val ids = mutableListOf<Int>()
        }

        private val root = Node()
        private var size = 0

        init {
            for ((id, raw) in signatures) {
                val signature = raw.replace('/', '.').trim('.')
                if (signature.isEmpty()) continue
                var node = root
                for (char in signature) {
                    node = node.children.getOrPut(char) { Node() }
                }
                node.ids.add(id)
                size++
            }
        }

        fun isEmpty(): Boolean = size == 0

        fun match(className: String, matched: MutableSet<Int>) {
            var node = root
            for (index in className.indices) {
                node = node.children[className[index]] ?: return
                if (node.ids.isNotEmpty() &&
                    (index == className.lastIndex || className[index + 1] in ". \$")
                ) {
                    matched.addAll(node.ids)
                }
            }
        }

        fun size(): Int = size
    }

    private object Header {
        const val SIZE = 112
        const val STRING_IDS_SIZE_OFF = 56
        const val STRING_IDS_OFF_OFF = 60
        const val TYPE_IDS_SIZE_OFF = 64
        const val TYPE_IDS_OFF_OFF = 68
    }
}
