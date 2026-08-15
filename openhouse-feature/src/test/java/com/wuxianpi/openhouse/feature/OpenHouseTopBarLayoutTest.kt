package com.wuxianpi.openhouse.feature

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenHouseTopBarLayoutTest {
    @Test
    fun setupAttentionSharesTheExistingTopBarRow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = LayoutInflater.from(context).inflate(R.layout.activity_open_house, null)
        val topBar = root.findViewById<LinearLayout>(R.id.oh_top_bar)
        val titleGroup = root.findViewById<LinearLayout>(R.id.oh_title_group)
        val title = root.findViewById<TextView>(R.id.oh_title)
        val attention = root.findViewById<TextView>(R.id.oh_setup_attention)

        assertSame(topBar, titleGroup.parent)
        assertSame(titleGroup, title.parent)
        assertSame(titleGroup, attention.parent)
        assertEquals(LinearLayout.HORIZONTAL, topBar.orientation)
        assertEquals(LinearLayout.HORIZONTAL, titleGroup.orientation)
        assertEquals(1f, (titleGroup.layoutParams as LinearLayout.LayoutParams).weight)
        assertEquals(0, attention.layoutParams.width)
        assertEquals(1f, (attention.layoutParams as LinearLayout.LayoutParams).weight)
        assertEquals(View.INVISIBLE, attention.visibility)
        assertEquals(1, attention.maxLines)
    }
}
