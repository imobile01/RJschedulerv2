package com.rj.scheduler;

import android.app.AlarmManager;
import android.app.Notification;
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

/** Background email sender: fires at each pay-period cut-off even when the app is closed. */
final class Sched {
    static final String PREFS = "rj";
    static final long SEND_DELAY = 90_000L;      // run 90 s after 11:59 PM so an open app gets first go
    static final long RETRY = 15 * 60_000L;      // retry every 15 min when offline
    static final int MAX_TRIES = 48;             // ~12 hours of retries
    private static final int FLAG_IMMUTABLE = 0x04000000;
    private static final String CHANNEL = "sends";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void setPending(Context c, String json, long cutoff) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (json == null || json.isEmpty()) {
            e.remove("pending").remove("tries").remove("retryAt").apply();
            cancel(c);
            return;
        }
        e.putString("pending", json).putInt("tries", 0).remove("retryAt").apply();
        schedule(c, cutoff + SEND_DELAY);
    }

    static void reschedule(Context c) {
        SharedPreferences p = prefs(c);
        String json = p.getString("pending", "");
        if (json.isEmpty()) return;
        try {
            long cutoff = new JSONObject(json).getLong("cutoff");
            long at = Math.max(p.getLong("retryAt", cutoff + SEND_DELAY), System.currentTimeMillis() + 60_000L);
            schedule(c, at);
        } catch (Exception ignored) { }
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, SendReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    }

    static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent(c);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31) {
            try { exact = (Boolean) AlarmManager.class.getMethod("canScheduleExactAlarms").invoke(am); }
            catch (Exception e) { exact = false; }
        }
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static void cancel(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(c));
    }

    /** Runs on a background thread from SendReceiver. */
    static void run(Context c) {
        SharedPreferences p = prefs(c);
        String json = p.getString("pending", "");
        if (json.isEmpty()) return;
        JSONObject j;
        long cutoff;
        try { j = new JSONObject(json); cutoff = j.getLong("cutoff"); }
        catch (Exception e) { p.edit().remove("pending").apply(); return; }

        long now = System.currentTimeMillis();
        if (now < cutoff) { schedule(c, cutoff + SEND_DELAY); return; }   // fired early (clock change)

        String label = j.optString("label", j.optString("subject", "Pay period"));
        if (j.optBoolean("notifyOnly")) {
            p.edit().remove("pending").apply();
            notify(c, "Pay period closed", label + " is ready. Tap to send it to " + j.optString("to"), 2);
            return;
        }

        String to = j.optString("to");
        try {
            String reply = post(j.getString("url"), json);
            JSONObject r = new JSONObject(reply);
            if (r.optBoolean("ok")) {
                JSONObject sent = new JSONObject(p.getString("sent", "{}"));
                JSONObject s = new JSONObject();
                s.put("to", to);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                s.put("at", f.format(new Date()));
                sent.put(String.valueOf(cutoff), s);
                p.edit().putString("sent", sent.toString()).remove("pending").remove("tries").remove("retryAt").apply();
                notify(c, "Records emailed", subjectLabel(j) + " sent to " + to, 1);
            } else {
                p.edit().remove("pending").apply();
                notify(c, "Couldn't email records", subjectLabel(j) + ": " + r.optString("error", "rejected")
                        + ". Open RJ-Scheduler to check the sender link and key.", 3);
            }
        } catch (Exception e) {
            int tries = p.getInt("tries", 0) + 1;
            if (tries >= MAX_TRIES) {
                p.edit().remove("pending").apply();
                notify(c, "Records not sent yet", "No internet. Open RJ-Scheduler to send " + subjectLabel(j) + ".", 3);
            } else {
                long at = now + RETRY;
                p.edit().putInt("tries", tries).putLong("retryAt", at).apply();
                schedule(c, at);
            }
        }
    }

    private static String subjectLabel(JSONObject j) {
        String s = j.optString("subject", "");
        int k = s.indexOf("— ");
        return k >= 0 ? s.substring(k + 2) : (s.isEmpty() ? "Pay period" : s);
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(20_000);
        h.setReadTimeout(45_000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
        OutputStream o = h.getOutputStream();
        o.write(body.getBytes("UTF-8"));
        o.close();
        int code = h.getResponseCode();
        if (code >= 300 && code < 400) {                 // Apps Script answers via a redirect
            String loc = h.getHeaderField("Location");
            h.disconnect();
            for (int i = 0; i < 5 && loc != null; i++) {
                HttpURLConnection g = (HttpURLConnection) new URL(loc).openConnection();
                g.setInstanceFollowRedirects(false);
                g.setConnectTimeout(20_000);
                g.setReadTimeout(45_000);
                int gc = g.getResponseCode();
                if (gc >= 300 && gc < 400) { loc = g.getHeaderField("Location"); g.disconnect(); continue; }
                return read(g);
            }
            throw new Exception("too many redirects");
        }
        return read(h);
    }

    private static String read(HttpURLConnection h) throws Exception {
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
        }
        h.disconnect();
        if (code >= 400) throw new Exception("HTTP " + code);
        return b.toString("UTF-8");
    }

    @SuppressWarnings("deprecation")
    static void notify(Context c, String title, String text, int id) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Automatic sending", 3);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, CHANNEL);
            } else {
                b = new Notification.Builder(c);
            }
            Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
            b.setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle(title).setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi);
            nm.notify(id, b.build());
        } catch (Exception ignored) { }
    }
}
