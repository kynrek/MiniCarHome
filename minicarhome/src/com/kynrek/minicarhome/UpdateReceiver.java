package com.kynrek.minicarhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

/**
 * An update stops the running home screen, and Android doesn't bring it back until Home is pressed, which left
 * the panel gone over the main display app. If this app is the home screen, open it again.
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = c.getPackageManager().resolveActivity(home, 0);
        if (r == null || r.activityInfo == null || !c.getPackageName().equals(r.activityInfo.packageName)) return;
        // Starting from the background is allowed because the panel's overlay permission is granted.
        c.startActivity(home.setPackage(c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
