package com.flowstate.focus.widgets

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRemoteViewsLayoutTest {
    private val layoutDirectory = File("src/main/res/layout")
    private val layouts = layoutDirectory.listFiles { file ->
        file.isFile && file.name.startsWith("widget_") && file.extension == "xml"
    }?.sortedBy { it.name }.orEmpty()

    @Test
    fun everyWidgetHasExactlyOneLayout() {
        // 1.8.8: one layout per widget; dark and light are switched at runtime, so there are no *_light copies.
        val expected = listOf(
            "widget_break_mode.xml", "widget_daily_progress.xml", "widget_exam_countdown.xml", "widget_focus_ring.xml",
            "widget_group_focus.xml", "widget_now_focusing.xml", "widget_quick_start.xml", "widget_streak_heatmap.xml",
            "widget_syllabus_rings.xml", "widget_today_tasks.xml",
        )
        assertEquals(expected, layouts.map { it.name })
    }

    @Test
    fun widgetLayoutsUseOnlyRemoteViewsSupportedClasses() {
        // RemoteViews does not allow a plain android.view.View (or Space); use a supported ImageView for decoration.
        // Chronometer is allowed: the timer widgets tick with it instead of being redrawn every second.
        val supportedClasses = setOf("LinearLayout", "FrameLayout", "TextView", "ImageView", "ProgressBar", "Chronometer")
        layouts.forEach { layout ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout)
            val elements = document.getElementsByTagName("*")
            val unsupported = (0 until elements.length)
                .map { elements.item(it).nodeName }
                .filterNot { it in supportedClasses }
            assertTrue("${layout.name} contains unsupported RemoteViews classes: $unsupported", unsupported.isEmpty())
        }
    }

    @Test
    fun everyLayoutHasAGlassRootAndEveryWidgetInfoPointsAtOne() {
        layouts.forEach { layout ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout).documentElement
            assertEquals("${layout.name} root id", "@+id/widget_root", root.getAttribute("android:id"))
            assertTrue("${layout.name} starts on glass", root.getAttribute("android:background").startsWith("@drawable/widget_glass_"))
        }
        val infos = File("src/main/res/xml").listFiles { f -> f.name.startsWith("widget_info_") }.orEmpty()
        assertEquals(10, infos.size)
        val names = layouts.map { it.nameWithoutExtension }.toSet()
        infos.forEach { info ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(info).documentElement
            val initial = root.getAttribute("android:initialLayout").removePrefix("@layout/")
            val preview = root.getAttribute("android:previewLayout").removePrefix("@layout/")
            assertTrue("${info.name} initialLayout", initial in names)
            assertTrue("${info.name} previewLayout", preview in names)
            assertTrue("${info.name} description", root.getAttribute("android:description").startsWith("@string/"))
            assertTrue("${info.name} target cells", root.getAttribute("android:targetCellWidth").isNotEmpty() && root.getAttribute("android:targetCellHeight").isNotEmpty())
        }
    }

    @Test
    fun everyProviderIsRegisteredInTheManifest() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        WidgetKind.entries.forEach { kind ->
            assertTrue("${kind.provider.simpleName} missing from the manifest", manifest.contains(".widgets.${kind.provider.simpleName}\""))
        }
    }
}
