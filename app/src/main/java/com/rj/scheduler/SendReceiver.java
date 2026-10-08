package com.rj.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

public class SendReceiver extends BroadcastReceiver {
    @Override public void onReceive(final Context ctx, Intent intent) {
        final PendingResult res = goAsync();
        final Context c = ctx.getApplicationContext();
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rjscheduler:send");
        wl.acquire(55_000L);
        new Thread(new Runnable() {
            @Override public void run() {
                try { Sched.run(c); }
                finally { if (wl.isHeld()) wl.release(); res.finish(); }
            }
        }).start();
    }
}
