package com.stayclean;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Active device admin = Android refuses to uninstall the app until admin is removed first. */
public class AdminReceiver extends DeviceAdminReceiver {
    @Override public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Stay Clean will stop guarding you at night.";
    }
}
