package com.smartcart.app

import androidx.test.platform.app.InstrumentationRegistry
import com.smartcart.data.FileFixtureSource
import com.smartcart.data.FixtureSource
import java.io.File

/** Contract fixtures: read from disk on the JVM, from the APK's bundled assets on a device. */
object TestFixtures {
    fun source(): FixtureSource =
        System.getProperty("fixturesDir")?.let { FileFixtureSource(File(it)) }
            ?: AssetFixtureSource(InstrumentationRegistry.getInstrumentation().targetContext)
}
