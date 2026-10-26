package com.example.myplan.focus

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.myplan.focus.service.AppLockAccessibilityService
import com.example.myplan.focus.util.PermissionHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class FocusPermissionTest {
    @Test fun staleOrPrefixServiceNameCannotMakeFocusPermissionReady() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val target = ComponentName(context, AppLockAccessibilityService::class.java).flattenToString()
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, target)
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        assertFalse(PermissionHelper.hasAccessibilityPermission(context))
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            context.packageName + ".other/.SomeAccessibilityService")
        assertFalse(PermissionHelper.hasAccessibilityPermission(context))
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.app/.AccessibilityService:" + target)
        assertTrue(PermissionHelper.hasAccessibilityPermission(context))
    }
}
