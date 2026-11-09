package com.oshare.airdrop;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bridge to the LSPosed framework service (libxposed service API).
 *
 * When the framework is active and this module is enabled, LSPosed injects
 * our app process and delivers an {@code IXposedService} binder to the
 * {@code <pkg>.XposedService} content provider (see XposedServiceGate).
 * Through that binder a module can query its scope and — most importantly —
 * request scope grant: LSPosed then shows its own authorization prompt or
 * notification, no manager navigation required.
 *
 * The remote interface is spoken with raw parcels (no AIDL sources needed).
 * Transaction codes follow libxposed service API 101/102:
 *   getApiVersion() = 1
 *   getScope() = 10
 *   requestScope(List<String>, IXposedScopeCallback) = 11  (oneway)
 *   removeScope(List<String>) = 12
 */
public final class LspServiceBridge {

    private static final String DESCRIPTOR =
        "io.github.libxposed.service.IXposedService";
    private static final String CALLBACK_DESCRIPTOR =
        "io.github.libxposed.service.IXposedScopeCallback";

    /** Delivered when the framework binder arrives (or immediately if it
     *  already has). */
    public interface Receiver {
        void onServiceBound(LspServiceBridge service);
    }

    /** Outcome of a scope request, reported by LSPosed. */
    public interface ScopeCallback {
        void onScopeApproved();
        void onScopeFailed(String message);
    }

    private static volatile LspServiceBridge sInstance;
    private static volatile Receiver sReceiver;

    private final IBinder mBinder;

    private LspServiceBridge(IBinder binder) {
        mBinder = binder;
    }

    /** Entry point from XposedServiceGate.call("SendBinder"). */
    static void onBinderReceived(IBinder binder) {
        if (binder == null) return;
        LspServiceBridge service = new LspServiceBridge(binder);
        sInstance = service;
        Receiver receiver = sReceiver;
        if (receiver != null) {
            try {
                receiver.onServiceBound(service);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Register early (e.g. activity onCreate). Safe to call any time: if
     *  the binder was delivered before registration, the receiver fires at
     *  once with the cached instance. */
    public static void register(Receiver receiver) {
        sReceiver = receiver;
        LspServiceBridge service = sInstance;
        if (service != null) receiver.onServiceBound(service);
    }

    /** True while the framework's service binder is alive and answering. */
    public boolean isAlive() {
        try {
            return mBinder.isBinderAlive() && apiVersion() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Service API version implemented by the framework (101/102). */
    public int apiVersion() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            mBinder.transact(1, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RemoteException e) {
            return 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Packages currently granted to this module by the framework. */
    public List<String> getScope() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            mBinder.transact(10, data, reply, 0);
            reply.readException();
            ArrayList<String> out = reply.createStringArrayList();
            return out != null ? out : Collections.emptyList();
        } catch (RemoteException e) {
            return Collections.emptyList();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Ask LSPosed to grant scope to the given packages. LSPosed surfaces
     *  its own confirmation prompt / notification; the result comes back
     *  through {@code callback}. */
    public void requestScope(List<String> packages, ScopeCallback callback) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeStringList(packages);
            data.writeStrongBinder(new ScopeCallbackBinder(callback));
            // oneway — no reply parcel, FLAG_ONEWAY
            mBinder.transact(11, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
        } finally {
            data.recycle();
        }
    }

    /** Minimal IXposedScopeCallback binder: transact 1 = approved(list),
     *  transact 2 = failed(message). */
    private static final class ScopeCallbackBinder extends Binder {
        private final ScopeCallback mCallback;

        ScopeCallbackBinder(ScopeCallback callback) {
            mCallback = callback;
        }

        @Override
        public String getInterfaceDescriptor() {
            return CALLBACK_DESCRIPTOR;
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply,
                                  int flags) {
            try {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
            } catch (Exception ignored) {
                return true;
            }
            if (code == 1) {
                // onScopeRequestApproved(List<String> approved)
                List<String> approved = data.createStringArrayList();
                mCallback.onScopeApproved();
            } else if (code == 2) {
                // onScopeRequestFailed(String message)
                String message = data.readString();
                mCallback.onScopeFailed(message != null ? message : "未知错误");
            }
            return true;
        }
    }
}
