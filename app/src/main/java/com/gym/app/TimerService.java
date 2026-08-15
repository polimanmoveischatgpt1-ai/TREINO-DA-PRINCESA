package com.gym.app;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.*;

public class TimerService extends Service {
    public static final String PREFS = "gym_timer";
    public static final String ACTION_START = "com.gym.app.START";
    public static final String ACTION_ADD = "com.gym.app.ADD";
    public static final String ACTION_PAUSE = "com.gym.app.PAUSE";
    public static final String ACTION_RESUME = "com.gym.app.RESUME";
    public static final String ACTION_STOP = "com.gym.app.STOP";

    private static final String TIMER_CHANNEL = "gym_timer_active";
    private static final String FINISH_SOUND_CHANNEL = "gym_timer_finish_sound";
    private static final String FINISH_SILENT_CHANNEL = "gym_timer_finish_silent";
    private static final int TIMER_ID = 40;
    private static final int FINISH_ID = 41;

    private Handler handler;
    private Runnable finishRunnable;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            restoreIfNeeded();
            return START_STICKY;
        }
        switch (action) {
            case ACTION_START -> startTimer(
                    Math.max(1, intent.getIntExtra("seconds", 90)),
                    intent.getStringExtra("label"),
                    intent.getBooleanExtra("vibration", true),
                    intent.getBooleanExtra("sound", true));
            case ACTION_ADD -> addTime(Math.max(1, intent.getIntExtra("seconds", 15)));
            case ACTION_PAUSE -> pauseTimer();
            case ACTION_RESUME -> resumeTimer();
            case ACTION_STOP -> stopTimer(true);
        }
        return START_STICKY;
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel timer = new NotificationChannel(TIMER_CHANNEL, "Cronômetro de descanso", NotificationManager.IMPORTANCE_LOW);
        timer.setDescription("Mostra o descanso em andamento");
        timer.setSound(null, null);
        timer.enableVibration(false);
        timer.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(timer);

        Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build();
        NotificationChannel finishSound = new NotificationChannel(FINISH_SOUND_CHANNEL, "Fim do descanso com som", NotificationManager.IMPORTANCE_HIGH);
        finishSound.setDescription("Avisa quando o descanso termina");
        finishSound.setSound(soundUri, attrs);
        finishSound.enableVibration(false);
        finishSound.enableLights(true);
        finishSound.setLightColor(Color.rgb(71, 118, 255));
        nm.createNotificationChannel(finishSound);

        NotificationChannel finishSilent = new NotificationChannel(FINISH_SILENT_CHANNEL, "Fim do descanso silencioso", NotificationManager.IMPORTANCE_HIGH);
        finishSilent.setDescription("Avisa visualmente quando o descanso termina");
        finishSilent.setSound(null, null);
        finishSilent.enableVibration(false);
        nm.createNotificationChannel(finishSilent);
    }

    private void startTimer(int seconds, String label, boolean vibration, boolean sound) {
        long remainingMs = seconds * 1000L;
        long endElapsed = SystemClock.elapsedRealtime() + remainingMs;
        String safeLabel = (label == null || label.isBlank()) ? "Descanso" : label;
        prefs.edit()
                .putBoolean("active", true)
                .putBoolean("running", true)
                .putLong("endElapsed", endElapsed)
                .putLong("pausedMs", 0L)
                .putString("label", safeLabel)
                .putBoolean("vibration", vibration)
                .putBoolean("sound", sound)
                .apply();
        acquireWakeLock(remainingMs + 30_000L);
        startOrUpdateForeground(buildActiveNotification(remainingMs, safeLabel, true));
        scheduleFinish(remainingMs);
    }

    private void addTime(int seconds) {
        if (!prefs.getBoolean("active", false)) return;
        boolean running = prefs.getBoolean("running", false);
        long addMs = seconds * 1000L;
        String label = prefs.getString("label", "Descanso");
        if (running) {
            long end = prefs.getLong("endElapsed", SystemClock.elapsedRealtime()) + addMs;
            prefs.edit().putLong("endElapsed", end).apply();
            long rem = Math.max(0, end - SystemClock.elapsedRealtime());
            acquireWakeLock(rem + 30_000L);
            startOrUpdateForeground(buildActiveNotification(rem, label, true));
            scheduleFinish(rem);
        } else {
            long rem = prefs.getLong("pausedMs", 0L) + addMs;
            prefs.edit().putLong("pausedMs", rem).apply();
            startOrUpdateForeground(buildActiveNotification(rem, label, false));
        }
    }

    private void pauseTimer() {
        if (!prefs.getBoolean("active", false) || !prefs.getBoolean("running", false)) return;
        long rem = Math.max(0L, prefs.getLong("endElapsed", 0L) - SystemClock.elapsedRealtime());
        prefs.edit().putBoolean("running", false).putLong("pausedMs", rem).apply();
        cancelFinish();
        releaseWakeLock();
        startOrUpdateForeground(buildActiveNotification(rem, prefs.getString("label", "Descanso"), false));
    }

    private void resumeTimer() {
        if (!prefs.getBoolean("active", false) || prefs.getBoolean("running", false)) return;
        long rem = Math.max(0L, prefs.getLong("pausedMs", 0L));
        if (rem <= 0) { finishTimer(); return; }
        long end = SystemClock.elapsedRealtime() + rem;
        prefs.edit().putBoolean("running", true).putLong("endElapsed", end).apply();
        acquireWakeLock(rem + 30_000L);
        startOrUpdateForeground(buildActiveNotification(rem, prefs.getString("label", "Descanso"), true));
        scheduleFinish(rem);
    }

    private void restoreIfNeeded() {
        if (!prefs.getBoolean("active", false)) { stopSelf(); return; }
        String label = prefs.getString("label", "Descanso");
        if (prefs.getBoolean("running", false)) {
            long rem = prefs.getLong("endElapsed", 0L) - SystemClock.elapsedRealtime();
            if (rem <= 0) { finishTimer(); return; }
            acquireWakeLock(rem + 30_000L);
            startOrUpdateForeground(buildActiveNotification(rem, label, true));
            scheduleFinish(rem);
        } else {
            long rem = prefs.getLong("pausedMs", 0L);
            if (rem <= 0) { stopTimer(false); return; }
            startOrUpdateForeground(buildActiveNotification(rem, label, false));
        }
    }

    private Notification buildActiveNotification(long remainingMs, String label, boolean running) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, TIMER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Gym • " + label)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setVisibility(Notification.VISIBILITY_PUBLIC);

        if (running) {
            b.setContentText("Descanso em andamento")
             .setWhen(System.currentTimeMillis() + remainingMs)
             .setUsesChronometer(true)
             .setChronometerCountDown(true);
        } else {
            b.setContentText("Pausado • " + formatTime(remainingMs));
        }

        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_input_add, "+15s", actionIntent(ACTION_ADD, 15, 10)).build());
        b.addAction(new Notification.Action.Builder(
                running ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                running ? "Pausar" : "Continuar",
                actionIntent(running ? ACTION_PAUSE : ACTION_RESUME, 0, 11)).build());
        b.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", actionIntent(ACTION_STOP, 0, 12)).build());
        return b.build();
    }

    private PendingIntent actionIntent(String action, int seconds, int requestCode) {
        Intent i = new Intent(this, TimerService.class);
        i.setAction(action);
        i.putExtra("seconds", seconds);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void startOrUpdateForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(TIMER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(TIMER_ID, notification);
        }
    }

    private void scheduleFinish(long delayMs) {
        cancelFinish();
        finishRunnable = this::finishTimer;
        handler.postDelayed(finishRunnable, Math.max(1L, delayMs));
    }

    private void cancelFinish() {
        if (finishRunnable != null) handler.removeCallbacks(finishRunnable);
        finishRunnable = null;
    }

    private void finishTimer() {
        if (!prefs.getBoolean("active", false)) return;
        boolean vibrate = prefs.getBoolean("vibration", true);
        boolean sound = prefs.getBoolean("sound", true);
        String label = prefs.getString("label", "Descanso");
        prefs.edit().putBoolean("active", false).putBoolean("running", false).putLong("pausedMs", 0L).apply();
        cancelFinish();
        releaseWakeLock();

        if (vibrate) {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null) {
                long[] pattern = new long[]{0, 220, 100, 220, 100, 380};
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
                else vibrator.vibrate(pattern, -1);
            }
        }

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channel = sound ? FINISH_SOUND_CHANNEL : FINISH_SILENT_CHANNEL;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 20, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification done = new Notification.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Descanso finalizado")
                .setContentText(label + " • hora da próxima série")
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        nm.notify(FINISH_ID, done);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopTimer(boolean removeNotification) {
        prefs.edit().putBoolean("active", false).putBoolean("running", false).putLong("pausedMs", 0L).apply();
        cancelFinish();
        releaseWakeLock();
        stopForeground(removeNotification ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        stopSelf();
    }

    private void acquireWakeLock(long timeout) {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gym:RestTimer");
        if (!wakeLock.isHeld()) wakeLock.acquire(Math.max(60_000L, timeout));
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private String formatTime(long ms) {
        long total = Math.max(0, ms / 1000L);
        return String.format(java.util.Locale.getDefault(), "%02d:%02d", total / 60, total % 60);
    }

    @Override
    public void onDestroy() {
        cancelFinish();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
