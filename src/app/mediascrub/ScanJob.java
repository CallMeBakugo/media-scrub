package app.mediascrub;

import android.app.job.*;
import android.content.*;

public final class ScanJob extends JobService {
    private static final int ID = 43917;
    private Thread worker;

    static boolean schedule(Context context, boolean enabled) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!enabled) { scheduler.cancel(ID); return true; }
        if (scheduler.getPendingJob(ID) != null) return true;
        return scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(context, ScanJob.class))
            .setPeriodic(15 * 60 * 1000L).setPersisted(true).setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true).build()) == JobScheduler.RESULT_SUCCESS;
    }

    @Override public boolean onStartJob(JobParameters job) {
        worker = new Thread(() -> {
            try { Scanner.run(this, true, true); }
            finally { if (!Thread.currentThread().isInterrupted()) jobFinished(job, false); }
        }, "Media cleanup");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters job) {
        if (worker != null) worker.interrupt();
        return Scanner.prefs(this).getBoolean(Scanner.AUTO, false);
    }
}
