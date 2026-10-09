package com.rj.scheduler;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Woken by the 11:59 PM alarm. Hands the sending to SendJob, which waits for internet. */
public class SendReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SendJob.enqueue(context.getApplicationContext());
    }
}
