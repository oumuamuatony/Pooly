package io.pickuptogether.app

import android.app.Activity
import java.util.WeakHashMap

/** Own windows can cover Pleos' always-running map task even after it resumes. */
object NavigationWindows {
    private val activities = WeakHashMap<Activity, Boolean>()
    fun register(activity: Activity) { activities[activity] = false }
    fun unregister(activity: Activity) { activities.remove(activity) }
    fun isHidden(activity: Activity) = activities[activity] == true
    fun restore(activity: Activity) { activities[activity] = false; activity.setVisible(true) }
    fun revealNavigation() {
        activities.keys.toList().forEach { activity ->
            if (!activity.isDestroyed && !activity.isFinishing) {
                activities[activity] = true
                activity.setVisible(false)
                if (activity is MainActivity) activity.onNavigationWindowHidden()
            }
        }
    }
}
