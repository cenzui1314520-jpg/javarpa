package com.rpa.engine.shizuku;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * 手写的 AIDL 等价 binder 接口（工程约束纯 Java，不用 AIDL 插件）：
 * 安全起见只暴露 secure settings 的读/写两个结构化方法，不提供通用 shell 执行，
 * 避免任何调用路径把可拼接的命令串送进 shell。
 */
public interface ISettingsSvc extends IInterface {

    String getSecure(String key) throws RemoteException;

    void putSecure(String key, String value) throws RemoteException;

    String DESCRIPTOR = "com.rpa.engine.shizuku.ISettingsSvc";
    int TRANSACTION_GET_SECURE = Binder.FIRST_CALL_TRANSACTION;
    int TRANSACTION_PUT_SECURE = Binder.FIRST_CALL_TRANSACTION + 1;

    abstract class Stub extends Binder implements ISettingsSvc {

        public static ISettingsSvc asInterface(IBinder obj) {
            if (obj == null) return null;
            IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof ISettingsSvc) return (ISettingsSvc) iin;
            return new Proxy(obj);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            switch (code) {
                case TRANSACTION_GET_SECURE: {
                    data.enforceInterface(DESCRIPTOR);
                    String key = data.readString();
                    try {
                        String result = getSecure(key);
                        reply.writeNoException();
                        reply.writeString(result);
                    } catch (Exception e) {
                        reply.writeException(e);
                    }
                    return true;
                }
                case TRANSACTION_PUT_SECURE: {
                    data.enforceInterface(DESCRIPTOR);
                    String key = data.readString();
                    String value = data.readString();
                    try {
                        putSecure(key, value);
                        reply.writeNoException();
                    } catch (Exception e) {
                        reply.writeException(e);
                    }
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }

    class Proxy implements ISettingsSvc {

        private final IBinder remote;

        Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public String getSecure(String key) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(key);
                remote.transact(TRANSACTION_GET_SECURE, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        @Override
        public void putSecure(String key, String value) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeString(key);
                data.writeString(value);
                remote.transact(TRANSACTION_PUT_SECURE, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }

        @Override
        public IBinder asBinder() {
            return remote;
        }
    }
}
