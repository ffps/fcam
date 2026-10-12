package com.ffps.fcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = Prefs.load(c);
        if (p.autostart) {      // автозапуск не зависит от того, нажимали ли STOP
            p.enabled = true;   // кнопка в интерфейсе покажет STOP SERVICE
            p.save(c);
            CamService.start(c);
        }
    }
}
