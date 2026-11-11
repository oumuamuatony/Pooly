package io.pickuptogether.app

/** Rejects transient free-drive reports while the OEM replaces or calculates a route. */
class NavigationStopPolicy {
    private var requestedAt=0L
    private var inactiveAt=0L
    private var guided=false
    private var explicit=false
    fun begin(now: Long, confirmed: Boolean=false) { requestedAt=now;inactiveAt=0;guided=confirmed;explicit=false }
    fun cancelled() { explicit=true }
    fun observe(now: Long, active: Boolean, calculating: Boolean, confirmed: Boolean, pending: Boolean): Boolean {
        if(active||calculating){if(active)guided=true;inactiveAt=0;return false}
        if(pending&&now-requestedAt<10000){inactiveAt=0;return false}
        if(!guided&&!confirmed&&!explicit)return false
        if(inactiveAt==0L){inactiveAt=now;return false}
        return now-inactiveAt>=700
    }
}
