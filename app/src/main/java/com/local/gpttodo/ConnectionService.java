package com.local.gpttodo;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the same-phone OAuth callback alive while the user signs in in Chrome. */
public final class ConnectionService extends Service {
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("page_connection","원본 계정 연결",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"page_connection").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ChatGPT 원본 연결 중").setContentText("로그인과 목록 가져오기가 끝나면 자동 종료됩니다.")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(224203,notification);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}
}
