package com.rj.scheduler;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Automatic pay-period sending.
 *
 * The page hands over the next pay period (already built as Excel) with setPending().
 * An alarm fires shortly after the 11:59 PM cut-off; SendReceiver then starts SendJob,
 * which waits for internet and posts the email through the Google Apps Script link.
 */
final class Sched {

    static final String PREFS = "rj";
    static final long SEND_DELAY = 90_000L;      // send 90 s after the 11:59 PM cut-off
    static final int MAX_TRIES = 48;             // with 15-min retries about 12 hours
    private static final String CHANNEL = "sends";

    enum Result { DONE, RETRY }

    private Sched() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- called from the page ----------

    static void setPending(Context c, String json, long cutoff) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (json == null || json.isEmpty()) {
            e.remove("pending").remove("tries").apply();
            cancelAlarm(c);
            SendJob.cancel(c);
            return;
        }
        if (cutoff <= 0) {
            try { cutoff = new JSONObject(json).getLong("cutoff"); } catch (Exception ignored) { return; }
        }
        e.putString("pending", json).putInt("tries", 0).apply();
        scheduleAlarm(c, cutoff + SEND_DELAY);
    }

    /** After a restart, an app update, or opening the app: put the alarm back. */
    static void reschedule(Context c) {
        String p = prefs(c).getString("pending", "");
        if (p.isEmpty()) return;
        try {
            long at = new JSONObject(p).getLong("cutoff") + SEND_DELAY;
            // Missed while the phone was off: send a minute from now. The short wait lets the
            // page (if it is being opened) take over first, so the same period is never sent twice.
            scheduleAlarm(c, Math.max(at, System.currentTimeMillis() + 60_000L));
        } catch (Exception ignored) { }
    }

    // ---------- alarm ----------

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, SendReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleAlarm(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(c);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try { exact = am.canScheduleExactAlarms(); } catch (Exception e) { exact = false; }
        }
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void cancelAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmIntent(c));
    }

    // ---------- the actual send (runs inside SendJob, off the main thread) ----------

    /** Clear the hand-off only if the page hasn't replaced it with the next period meanwhile. */
    private static synchronized void clearIfSame(SharedPreferences sp, String handled) {
        if (handled.equals(sp.getString("pending", ""))) {
            sp.edit().remove("pending").remove("tries").apply();
        }
    }

    static Result run(Context c) {
        SharedPreferences sp = prefs(c);
        String p = sp.getString("pending", "");
        if (p.isEmpty()) return Result.DONE;

        JSONObject job;
        long cutoff;
        try {
            job = new JSONObject(p);
            cutoff = job.getLong("cutoff");
        } catch (Exception e) {
            clearIfSame(sp, p);
            return Result.DONE;
        }

        long now = System.currentTimeMillis();
        if (now < cutoff) {                       // woke up early: try again at the right time
            scheduleAlarm(c, cutoff + SEND_DELAY);
            return Result.DONE;
        }

        String label = job.optString("label", subjectLabel(job));
        String to = job.optString("to");

        if (job.optBoolean("notifyOnly")) {       // no sender link: just remind the user
            clearIfSame(sp, p);
            notify(c, "Pay period closed", label + " is ready. Tap to send it to " + to, 2);
            return Result.DONE;
        }

        try {
            JSONObject reply = new JSONObject(post(job.getString("url"), p));
            if (reply.optBoolean("ok")) {
                JSONObject sent = new JSONObject(sp.getString("sent", "{}"));
                JSONObject rec = new JSONObject();
                rec.put("to", to);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                rec.put("at", f.format(new Date()));
                sent.put(String.valueOf(cutoff), rec);
                sp.edit().putString("sent", sent.toString()).apply();
                clearIfSame(sp, p);
                notify(c, "Records emailed", subjectLabel(job) + " sent to " + to, 1);
            } else {                              // the script answered but refused (e.g. wrong key)
                clearIfSame(sp, p);
                notify(c, "Couldn't email records",
                        subjectLabel(job) + ": " + reply.optString("error", "rejected")
                                + ". Open RJ-Scheduler and go to Submit to send it.", 3);
            }
            return Result.DONE;
        } catch (Exception netProblem) {          // no internet / timeout: retry later
            int tries = sp.getInt("tries", 0) + 1;
            if (tries < MAX_TRIES) {
                sp.edit().putInt("tries", tries).apply();
                return Result.RETRY;
            }
            clearIfSame(sp, p);
            notify(c, "Records not sent yet",
                    "No internet. Open RJ-Scheduler to send " + subjectLabel(job) + ".", 3);
            return Result.DONE;
        }
    }

    /** POST to the Apps Script link and follow Google's redirect to read the reply. */
    private static String post(String url, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setInstanceFollowRedirects(false);
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int code = con.getResponseCode();
        if (code < 300 || code >= 400) return read(con);

        String loc = con.getHeaderField("Location");
        con.disconnect();
        for (int hop = 0; hop < 5 && loc != null; hop++) {
            HttpURLConnection r = (HttpURLConnection) new URL(loc).openConnection();
            r.setInstanceFollowRedirects(false);
            r.setConnectTimeout(20_000);
            r.setReadTimeout(60_000);
            int rc = r.getResponseCode();
            if (rc < 300 || rc >= 400) return read(r);
            loc = r.getHeaderField("Location");
            r.disconnect();
        }
        throw new Exception("too many redirects");
    }

    private static String read(HttpURLConnection con) throws Exception {
        int code = con.getResponseCode();
        InputStream in = code < 400 ? con.getInputStream() : con.getErrorStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
        }
        con.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return out.toString("UTF-8");
    }

    private static String subjectLabel(JSONObject j) {
        String s = j.optString("subject", "");
        int i = s.indexOf("\u2014 ");
        if (i >= 0) return s.substring(i + 2);
        return s.isEmpty() ? "Pay period" : s;
    }

    // ---------- notifications ----------

    static void notify(Context c, String title, String text, int id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "Automatic sending", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(c, CHANNEL);
            } else {
                b = new Notification.Builder(c);
            }
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent tap = PendingIntent.getActivity(c, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(tap);
            nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }
}
