package com.flowstate.focus.widgets

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRemoteViewsLayoutTest {
    @Test
    fun widgetLayoutsUseOnlyRemoteViewsSupportedClasses() {
        val layoutDirectory = File("src/main/res/layout")
        val layouts = layoutDirectory.listFiles { file ->
            file.isFile && file.name.startsWith("widget_") && file.extension == "xml"
        }?.sortedBy { it.name }.orEmpty()

        assertEquals("Expected the four light/dark layouts for the two widgets", 4, layouts.size)

        // RemoteViews does not allow a plain android.view.View; use a supported ImageView for decoration.
        val supportedClasses = setOf("LinearLayout", "FrameLayout", "TextView", "ImageView", "ProgressBar")
        layouts.forEach { layout ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
            val elements = document.getElementsByTagName("*")
            val unsupported = (0 until elements.length)
                .map { elements.item(it).nodeName }
                .filterNot { it in supportedClasses }
            assertTrue("${layout.name} contains unsupported RemoteViews classes: $unsupported", unsupported.isEmpty())
        }
    }
}
