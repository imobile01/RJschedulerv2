package com.rj.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android clears alarms on restart and app update; this puts the 11:59 PM alarm back. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Sched.reschedule(context.getApplicationContext());
        }
    }
}
