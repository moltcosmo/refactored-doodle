package id.printjobmanager.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;

/** Local, offline reminders for estimated production-stage completion times. */
public class PJMNotificationReceiver extends BroadcastReceiver {
    static final String ACTION_STAGE_ESTIMATE = "id.printjobmanager.app.STAGE_ESTIMATE";
    static final String CHANNEL_ID = "pjm_production_estimates_v2";

    static PendingIntent pendingIntent(Context context, long stageId) {
        Intent i = new Intent(context, PJMNotificationReceiver.class);
        i.setAction(ACTION_STAGE_ESTIMATE);
        i.putExtra("stage_id", stageId);
        return PendingIntent.getBroadcast(context, (int) stageId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context, long stageId, long estimateAt) {
        cancel(context, stageId);
        if (stageId <= 0 || estimateAt <= System.currentTimeMillis()) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, estimateAt, pendingIntent(context, stageId));
    }

    static void cancel(Context context, long stageId) {
        if (stageId <= 0) return;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pendingIntent(context, stageId);
        if (alarms != null) alarms.cancel(pi);
        pi.cancel();
    }

    static void rebuild(Context context) {
        java.io.File file = context.getDatabasePath("print_job_manager.db");
        if (!file.exists()) return;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT id,estimated_at FROM project_stages WHERE status<>'completed' AND estimated_at>?", new String[]{String.valueOf(System.currentTimeMillis())});
            while (c.moveToNext()) schedule(context, c.getLong(0), c.getLong(1));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Pengingat produksi (pop-up)", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifikasi pop-up saat estimasi penyelesaian tahap produksi tercapai");
                channel.enableVibration(true);
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            rebuild(context);
            return;
        }
        if (!ACTION_STAGE_ESTIMATE.equals(intent.getAction())) return;
        long stageId = intent.getLongExtra("stage_id", 0);
        if (stageId <= 0) return;
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            java.io.File file = context.getDatabasePath("print_job_manager.db");
            if (!file.exists()) return;
            db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            c = db.rawQuery("SELECT s.name,p.name,s.estimated_at,s.status FROM project_stages s JOIN projects p ON p.id=s.project_id WHERE s.id=?", new String[]{String.valueOf(stageId)});
            if (!c.moveToFirst() || "completed".equals(c.getString(3))) return;
            long estimate = c.getLong(2);
            if (estimate <= 0 || estimate > System.currentTimeMillis() + 60000) return;
            String stageName = c.getString(0);
            String projectName = c.getString(1);
            ensureChannel(context);
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent content = PendingIntent.getActivity(context, (int) stageId, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentTitle("Estimasi tahap produksi tercapai")
                    .setContentText(projectName + " · " + stageName)
                    .setStyle(new Notification.BigTextStyle().bigText("Periksa pekerjaan " + projectName + ". Estimasi tahap “" + stageName + "” sudah tercapai."))
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build();
            manager.notify((int) stageId, notification);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
            if (db != null) db.close();
        }
    }
}
