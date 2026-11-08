package com.oshare.airdrop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Receives the LSPosed framework service binder.
 *
 * LSPosed (while the module is enabled) injects this app's process and
 * invokes the {@code <pkg>.XposedService} provider with
 * call("SendBinder", extras{binder}) — the standard libxposed service
 * handshake. The binder is handed over to {@link LspServiceBridge}.
 */
public final class XposedServiceGate extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("SendBinder".equals(method) && extras != null) {
            android.os.IBinder binder = extras.getBinder("binder");
            if (binder != null) {
                LspServiceBridge.onBinderReceived(binder);
            }
            return new Bundle();
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
