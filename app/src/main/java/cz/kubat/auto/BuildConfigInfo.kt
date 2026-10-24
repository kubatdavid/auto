package cz.kubat.auto

import android.content.Context

object BuildConfigInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
