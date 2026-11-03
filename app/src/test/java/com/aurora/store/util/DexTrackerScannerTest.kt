/*
 * SPDX-FileCopyrightText: 2026 Aurora OSS
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DexTrackerScannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Minimal valid DEX: header + 2 strings + 1 type pointing at the first string. */
    private fun syntheticDex(first: String, second: String): ByteArray {
        val firstBytes = first.toByteArray(Charsets.UTF_8)
        val secondBytes = second.toByteArray(Charsets.UTF_8)
        // string_data items are uleb128 length + bytes + 0x00; lengths here fit one byte.
        val data0Off = 112 + 2 * Int.SIZE_BYTES + Int.SIZE_BYTES
        val data1Off = data0Off + 1 + firstBytes.size + 1
        val total = data1Off + 1 + secondBytes.size + 1
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("dex\n035\u0000".toByteArray(Charsets.US_ASCII))
        buffer.position(56)
        buffer.putInt(2) // string_ids_size
        buffer.putInt(112) // string_ids_off
        buffer.putInt(1) // type_ids_size
        buffer.putInt(120) // type_ids_off
        buffer.position(112)
        buffer.putInt(data0Off)
        buffer.putInt(data1Off)
        buffer.position(120)
        buffer.putInt(0) // descriptor_idx -> first string
        buffer.position(data0Off)
        buffer.put(firstBytes.size.toByte())
        buffer.put(firstBytes)
        buffer.put(0)
        buffer.position(data1Off)
        buffer.put(secondBytes.size.toByte())
        buffer.put(secondBytes)
        buffer.put(0)
        return buffer.array()
    }

    private fun apkWith(vararg dexFiles: Pair<String, ByteArray>): File {
        val apk = folder.newFile("test-${System.nanoTime()}.apk")
        ZipOutputStream(apk.outputStream().buffered()).use { zip ->
            for ((name, bytes) in dexFiles) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return apk
    }

    @Test
    fun matchesPrefixSignature() {
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        val found = DexTrackerScanner.scanApk(apk, mapOf(1 to "com.example", 2 to "com.nomatch"))
        assertThat(found).containsExactly(1)
    }

    @Test
    fun exactClassNameMatches() {
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        // Note: the synthetic DEX declares a single type pointing at the first string.
        val found = DexTrackerScanner.scanApk(apk, mapOf(7 to "com.example.Foo"))
        assertThat(found).containsExactly(7)
    }

    @Test
    fun prefixBoundaryIsRespected() {
        // "com.example.Fo" is a prefix of the class but not on a package boundary.
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        assertThat(DexTrackerScanner.scanApk(apk, mapOf(1 to "com.example.Fo"))).isEmpty()
    }

    @Test
    fun slashesAndDotsAreEquivalent() {
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        val found = DexTrackerScanner.scanApk(apk, mapOf(3 to "com/example"))
        assertThat(found).containsExactly(3)
    }

    @Test
    fun scansMultidex() {
        val apk = apkWith(
            "classes.dex" to syntheticDex("Lcom/one/A;", "Lcom/none/B;"),
            "classes2.dex" to syntheticDex("Lcom/two/C;", "Lcom/none/D;")
        )
        val found = DexTrackerScanner.scanApk(apk, mapOf(1 to "com.one", 2 to "com.two"))
        assertThat(found).containsExactly(1, 2)
    }

    @Test
    fun corruptDexYieldsEmptyInsteadOfThrowing() {
        val apk = apkWith("classes.dex" to byteArrayOf(1, 2, 3, 4, 5))
        assertThat(DexTrackerScanner.scanApk(apk, mapOf(1 to "com.example"))).isEmpty()
    }

    @Test
    fun nonZipFileYieldsEmpty() {
        val notApk = folder.newFile("bogus.apk").apply { writeText("not a zip") }
        assertThat(DexTrackerScanner.scanApk(notApk, mapOf(1 to "com.example"))).isEmpty()
    }

    @Test
    fun emptyInputsYieldEmpty() {
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        assertThat(DexTrackerScanner.scanApk(apk, emptyMap())).isEmpty()
        assertThat(
            DexTrackerScanner.scanApk(
                File(folder.root, "missing.apk"),
                mapOf(1 to "com.example")
            )
        ).isEmpty()
    }

    @Test
    fun blankSignaturesAreIgnored() {
        val apk = apkWith("classes.dex" to syntheticDex("Lcom/example/Foo;", "Lcom/other/Bar;"))
        assertThat(DexTrackerScanner.scanApk(apk, mapOf(1 to "  ", 2 to ""))).isEmpty()
    }
}
