package com.flowstate.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/** Dark Windows title bar in the app's colour (Windows 10 20H1+ dark mode, Windows 11 caption colour). */
object WindowsChrome {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    fun applyDark(window: Window) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        runCatching {
            val dwm = Native.load("dwmapi", Dwm::class.java)
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, IntByReference(1), 4)
            // COLORREF is 0x00BBGGRR: #070D11 caption, #CCD0CF title text. Ignored before Windows 11.
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, IntByReference(0x00110D07), 4)
            dwm.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, IntByReference(0x00CFD0CC), 4)
        }
    }
}
