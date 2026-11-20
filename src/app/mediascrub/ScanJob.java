package app.mediascrub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.util.HashMap;
import java.util.Map;

public final class ScanJob extends JobService {
    private static final int PERIODIC = 43917;
    private static final int MEDIA_CHANGE = 43918;
    static final String INTERVAL = "scan_interval_minutes";
    static final String LAST_SCAN = "last_automatic_scan";
    static final String LAST_TRIGGER = "last_automatic_trigger";
    static final String MEDIA_WATCH = "media_watch_scheduled";

    private final Map<Integer, Thread> workers = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    static int interval(SharedPreferences prefs) {
        int minutes = prefs.getInt(INTERVAL, 15);
        return minutes == 30 || minutes == 60 ? minutes : 15;
    }

    static boolean schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        SharedPreferences prefs = Scanner.prefs(context);
        if (!prefs.getBoolean(Scanner.AUTO, false)) {
            scheduler.cancel(PERIODIC);
            scheduler.cancel(MEDIA_CHANGE);
            return true;
        }

        long interval = interval(prefs) * 60_000L;
        JobInfo pending = scheduler.getPendingJob(PERIODIC);
        boolean periodic = pending != null && pending.getIntervalMillis() == interval;
        if (!periodic) periodic = submit(scheduler, builder(context, PERIODIC)
            .setPeriodic(interval).setPersisted(true).build());

        ensureMediaWatch(context, scheduler);
        return periodic;
    }

    private static JobInfo.Builder builder(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, ScanJob.class))
            .setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true);
    }

    private static boolean submit(JobScheduler scheduler, JobInfo job) {
        try {
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (IllegalStateException | SecurityException unavailable) {
            return false;
        }
    }

    private static void ensureMediaWatch(Context context, JobScheduler scheduler) {
        boolean ready = scheduler.getPendingJob(MEDIA_CHANGE) != null || armMediaWatch(context, scheduler);
        Scanner.prefs(context).edit().putBoolean(MEDIA_WATCH, ready).apply();
    }

    private static boolean armMediaWatch(Context context, JobScheduler scheduler) {
        JobInfo.Builder job = builder(context, MEDIA_CHANGE)
            // Batch related notifications and leave the file time to finish writing.
            .setTriggerContentUpdateDelay(Scanner.MIN_FILE_AGE_MS + 5_000L)
            .setTriggerContentMaxDelay(90_000L);
        int descendants = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS;
        for (String volume : new String[]{MediaStore.VOLUME_EXTERNAL, MediaStore.VOLUME_EXTERNAL_PRIMARY}) {
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Images.Media.getContentUri(volume), descendants));
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Video.Media.getContentUri(volume), descendants));
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Downloads.getContentUri(volume), descendants));
            job.addTriggerContentUri(new JobInfo.TriggerContentUri(MediaStore.Files.getContentUri(volume), descendants));
        }
        return submit(scheduler, job.build());
    }

    @Override public boolean onStartJob(JobParameters job) {
        int id = job.getJobId();
        SharedPreferences prefs = Scanner.prefs(this);
        if (!prefs.getBoolean(Scanner.AUTO, false)) return false;
        Thread worker = new Thread(() -> {
            Thread current = Thread.currentThread();
            String result = "";
            try {
                result = Scanner.run(this, true, true);
            } catch (RuntimeException failed) {
                if (!current.isInterrupted())
                    prefs.edit().putString(Scanner.RESULT, "Automatic scan could not complete.")
                        .remove(SkippedFiles.REPORT_ID).remove(SkippedFiles.COUNT).apply();
            } finally {
                String summary = result;
                main.post(() -> complete(job, current, summary));
            }
        }, "Automatic media cleanup");
        workers.put(id, worker);
        worker.start();
        return true;
    }

    private void complete(JobParameters job, Thread worker, String result) {
        int id = job.getJobId();
        if (workers.get(id) != worker) return;
        workers.remove(id);
        SharedPreferences prefs = Scanner.prefs(this);
        boolean enabled = prefs.getBoolean(Scanner.AUTO, false);
        if (enabled && !result.isEmpty() && !result.startsWith("Scan could not start.")) {
            prefs.edit().putLong(LAST_SCAN, System.currentTimeMillis())
                .putString(LAST_TRIGGER, id == MEDIA_CHANGE ? "Media change" : "Scheduled").apply();
        }
        JobScheduler scheduler = getSystemService(JobScheduler.class);
        if (id == MEDIA_CHANGE && enabled) {
            // Replacing this one-shot job registers the next media change,
            // including notifications that arrived while this scan was running.
            boolean armed = armMediaWatch(this, scheduler);
            prefs.edit().putBoolean(MEDIA_WATCH, armed).apply();
            if (armed) return;
        } else if (id == PERIODIC && enabled) {
            ensureMediaWatch(this, scheduler);
        }
        jobFinished(job, id == MEDIA_CHANGE && enabled);
    }

    @Override public boolean onStopJob(JobParameters job) {
        Thread worker = workers.remove(job.getJobId());
        if (worker != null) worker.interrupt();
        return Scanner.prefs(this).getBoolean(Scanner.AUTO, false);
    }
}
