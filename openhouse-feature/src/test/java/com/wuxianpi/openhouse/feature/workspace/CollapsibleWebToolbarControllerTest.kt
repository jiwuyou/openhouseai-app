package com.wuxianpi.openhouse.feature.workspace

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.wuxianpi.openhouse.feature.StartupRouteStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollapsibleWebToolbarControllerTest {
    @Test
    fun collapseOnlyAppliesToWebModeAndBubbleRestoresToolbar() {
        val context: Context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StartupRouteStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val host = FrameLayout(context)
        val toolbar = View(context)
        val bubble = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(52, 52)
        }
        host.addView(bubble)
        var clicks = 0
        val controller = CollapsibleWebToolbarController(context, host, toolbar, bubble) { clicks++ }

        controller.setWebMode(true)
        controller.collapse()
        assertEquals(View.GONE, toolbar.visibility)
        assertEquals(View.VISIBLE, bubble.visibility)

        bubble.performClick()
        assertEquals(1, clicks)
        assertEquals(View.VISIBLE, toolbar.visibility)
        assertEquals(View.GONE, bubble.visibility)

        controller.collapse()
        controller.setWebMode(false)
        assertEquals(View.VISIBLE, toolbar.visibility)
        assertEquals(View.GONE, bubble.visibility)
    }
}
