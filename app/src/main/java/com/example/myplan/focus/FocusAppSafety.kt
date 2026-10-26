package com.example.myplan.focus

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/** Never prevent the user from leaving focus, granting permissions, or making an emergency call. */
object FocusAppSafety {
    private val essentialPackages = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone",
        "com.android.server.telecom", "com.android.emergency", "com.google.android.apps.safetyhub",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller"
    )

    fun inputMethodPackages(context: Context): Set<String> =
        context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
            ?.map { it.packageName }?.toSet().orEmpty()

    fun filterPackages(context: Context, packages: Collection<String>): Set<String> {
        val excluded = essentialPackages.toMutableSet().apply {
            add(context.packageName)
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName?.let(::add)
            context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).forEach { add(it.activityInfo.packageName) }
            addAll(inputMethodPackages(context))
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
            Telephony.Sms.getDefaultSmsPackage(context)?.let(::add)
        }
        return packages.filterTo(linkedSetOf()) { it.isNotBlank() && it !in excluded }
    }

    fun selectableApps(context: Context, apps: List<Pair<String, String>>): List<Pair<String, String>> {
        val allowed = filterPackages(context, apps.map { it.first })
        return apps.filter { it.first in allowed }
    }
}
