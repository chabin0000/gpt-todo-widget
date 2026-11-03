package com.local.gpttodo;

import android.app.job.*;
import android.content.*;

public final class SyncJobService extends JobService {
    private static final int PERIODIC=224201,ON_DEMAND=224202;
    public static void schedule(Context context,boolean immediate){
        if(!SyncState.enabled(context))return;
        JobScheduler jobs=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        ComponentName service=new ComponentName(context,SyncJobService.class);
        if(jobs.getPendingJob(PERIODIC)==null)jobs.schedule(new JobInfo.Builder(PERIODIC,service)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build());
        if(immediate){
            JobInfo urgent=new JobInfo.Builder(ON_DEMAND,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExpedited(true).build();
            if(jobs.schedule(urgent)==JobScheduler.RESULT_FAILURE)jobs.schedule(new JobInfo.Builder(ON_DEMAND,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
        }
    }
    public static void cancel(Context c){JobScheduler jobs=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);jobs.cancel(PERIODIC);jobs.cancel(ON_DEMAND);}
    @Override public boolean onStartJob(JobParameters params){
        if(!SyncState.enabled(this))return false;
        PageSync.run(this,false,(success,message)->jobFinished(params,false));return true;
    }
    @Override public boolean onStopJob(JobParameters params){return false;}
}
