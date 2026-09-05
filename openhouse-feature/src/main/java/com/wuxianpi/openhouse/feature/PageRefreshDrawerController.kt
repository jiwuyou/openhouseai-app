package com.wuxianpi.openhouse.feature

import android.view.View
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.wuxianpi.openhouse.feature.workspace.EmbeddedWebPagePool

/** Controls the small end drawer that contains the two page reload actions. */
internal class PageRefreshDrawerController(
    private val drawer: DrawerLayout,
    private val pagePool: EmbeddedWebPagePool,
) {
    private val drawerView: View = drawer.findViewById(R.id.oh_page_refresh_drawer)
    private val navigationDrawer: View? = drawer.findViewById(R.id.oh_navigation_drawer)
    private val normalButton: View = drawerView.findViewById(R.id.oh_page_refresh_normal)
    private val forceButton: View = drawerView.findViewById(R.id.oh_page_refresh_force)
    private var available = false

    init {
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.END)
        drawerView.findViewById<View>(R.id.oh_page_refresh_close).setOnClickListener { close() }
        normalButton.setOnClickListener {
            close()
            pagePool.reloadActive()
        }
        forceButton.setOnClickListener {
            close()
            pagePool.forceReloadActive()
        }
    }

    fun setAvailable(value: Boolean) {
        available = value
        drawer.setDrawerLockMode(
            if (value) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.END,
        )
        if (!value) close()
        updateEnabledState()
    }

    fun open() {
        if (!available) return
        navigationDrawer?.let(drawer::closeDrawer)
        updateEnabledState()
        drawer.openDrawer(GravityCompat.END)
    }

    fun close() {
        drawer.closeDrawer(GravityCompat.END)
    }

    fun isOpen(): Boolean = drawer.isDrawerOpen(GravityCompat.END)

    fun handleBackPressed(): Boolean {
        if (!isOpen()) return false
        close()
        return true
    }

    fun updateEnabledState() {
        val enabled = pagePool.activeArgs != null
        normalButton.isEnabled = enabled
        forceButton.isEnabled = enabled
    }
}
