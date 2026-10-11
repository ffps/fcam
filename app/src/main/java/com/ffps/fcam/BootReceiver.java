package com.ffps.fcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        Prefs p = Prefs.load(c);
        if (p.enabled && p.autostart) {
            CamService.start(c);
        }
    }
}
