/*
 * Copyright 2026 timklge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.timklge.karooroutegraph.valhalla

import android.content.Context
import android.content.res.Resources
import android.os.Environment
import android.util.Log
import de.timklge.karooroutegraph.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class ValhallaTileCheckerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `lists unique level-2 tiles intersecting country bounds`() {
        val externalStorage = temporaryFolder.newFolder("external")
        val root = File(externalStorage, "offline/nav")
        Files.createDirectories(root.toPath())
        listOf(519120, 519121, 520560, 520561).forEach { createTile(root, 2, it) }
        createTile(root, 2, 519120, compressed = true)
        createTile(root, 2, 519122)
        createTile(root, 1, 519120)

        val context = mockk<Context>()
        val resources = mockk<Resources>()
        every { context.resources } returns resources
        every { resources.openRawResource(R.raw.countries) } returns ByteArrayInputStream(
            """{"AA":["Test country",[0.0,0.0,0.5,0.5],"Test"]}""".toByteArray()
        )

        mockkStatic(Environment::class)
        mockkStatic(Log::class)
        try {
            every { Environment.getExternalStorageDirectory() } returns externalStorage
            every { Log.e(any(), any(), any()) } returns 0
            every { Log.i(any(), any()) } returns 0
            val result = ValhallaTileChecker(context).listTiles()
            assertEquals(listOf(ValhallaTileChecker.TileInfo("Test country", 4)), result)
        } finally {
            unmockkStatic(Environment::class)
            unmockkStatic(Log::class)
        }
    }

    private fun createTile(root: java.io.File, level: Int, tileId: Int, compressed: Boolean = false) {
        val digits = tileId.toString().padStart(9, '0')
        val fileName = digits.substring(6) + if (compressed) ".gph.gz" else ".gph"
        val tileFile = root.toPath()
            .resolve(level.toString())
            .resolve(digits.substring(0, 3))
            .resolve(digits.substring(3, 6))
            .resolve(fileName)
        Files.createDirectories(tileFile.parent)
        Files.write(tileFile, byteArrayOf())
    }
}
