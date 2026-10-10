package app.onestream.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Borderless full screen: drops the frame, covers the whole monitor the window is on (taskbar included) and stays on top.
 * Done with Win32 calls on the live window, because AWT cannot change a shown window's decoration and its own full-screen mode is unreliable.
 */
object WinFullscreen {
    private const val STRIP = WinUser.WS_CAPTION or WinUser.WS_THICKFRAME or WinUser.WS_SYSMENU or WinUser.WS_MINIMIZEBOX or WinUser.WS_MAXIMIZEBOX
    private val HWND_TOPMOST = WinDef.HWND(Pointer.createConstant(-1L))
    private val HWND_NOTOPMOST = WinDef.HWND(Pointer.createConstant(-2L))
    private var style = 0L
    private var rect: WinDef.RECT? = null

    private fun hwnd(w: Window) = WinDef.HWND(Pointer(Native.getComponentID(w)))

    fun enter(w: Window) {
        val u = User32.INSTANCE
        val h = hwnd(w)
        val r = WinDef.RECT().also { u.GetWindowRect(h, it) }
        rect = r
        style = u.GetWindowLong(h, WinUser.GWL_STYLE).toLong()
        val mon = u.MonitorFromWindow(h, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO().also { u.GetMonitorInfo(mon, it) }
        val m = info.rcMonitor
        u.SetWindowLong(h, WinUser.GWL_STYLE, (style and STRIP.toLong().inv()).toInt())
        u.SetWindowPos(h, HWND_TOPMOST, m.left, m.top, m.right - m.left, m.bottom - m.top, WinUser.SWP_FRAMECHANGED or WinUser.SWP_SHOWWINDOW)
    }

    fun exit(w: Window) {
        val u = User32.INSTANCE
        val h = hwnd(w)
        val r = rect ?: return
        u.SetWindowLong(h, WinUser.GWL_STYLE, style.toInt())
        u.SetWindowPos(h, HWND_NOTOPMOST, r.left, r.top, r.right - r.left, r.bottom - r.top, WinUser.SWP_FRAMECHANGED or WinUser.SWP_SHOWWINDOW)
        rect = null
    }
}
