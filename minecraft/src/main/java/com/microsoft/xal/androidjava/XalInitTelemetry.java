package com.microsoft.xal.androidjava;

import android.content.Context;
import android.util.Log;

import com.microsoft.applications.events.HttpClient;

public final class XalInitTelemetry {

    private static final String TAG = "XalInitTelemetry";
    private static volatile boolean initialized = false;

    private XalInitTelemetry() {
        // No instances
    }

    /**
     * Called from native (JNI).
     * Must never throw.
     */
    public static void initOneDS(Context context) {
        if (context == null) {
            Log.e(TAG, "initOneDS called with null context");
            return;
        }

        if (initialized) {
            return;
        }

        synchronized (XalInitTelemetry.class) {
            if (initialized) {
                return;
            }

            try {
                System.loadLibrary("maesdk");
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Failed to load native library maesdk", e);
                return;
            }

            try {
                Context appContext = context.getApplicationContext();
                new HttpClient(appContext);
                initialized = true;
                Log.i(TAG, "OneDS telemetry initialized successfully");
            } catch (Throwable t) {
                // Catch Throwable to protect boundary
                Log.e(TAG, "OneDS initialization failed", t);
            }
        }
    }
}
