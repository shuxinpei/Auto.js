package org.autojs.autojs.pluginclient;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.AnyThread;
import android.support.annotation.MainThread;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.util.Pair;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.stardust.util.MapEntries;

import org.autojs.autojs.BuildConfig;
import org.autojs.autojs.tool.EmptyObservers;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;

/**
 * Created by Stardust on 2017/5/11.
 */

public class DevPluginService {

    private static final int CLIENT_VERSION = 2;
    private static final String LOG_TAG = "DevPluginService";
    private static final String TYPE_HELLO = "hello";
    private static final long HANDSHAKE_TIMEOUT = 10 * 1000;

    public static class State {

        public static final int DISCONNECTED = 0;
        public static final int CONNECTING = 1;
        public static final int CONNECTED = 2;

        private final int mState;
        private final Throwable mException;

        public State(int state, Throwable exception) {
            mState = state;
            mException = exception;
        }

        public State(int state) {
            this(state, null);
        }

        public int getState() {
            return mState;
        }

        public Throwable getException() {
            return mException;
        }
    }

    private static final int PORT = 9317;
    private static DevPluginService sInstance = new DevPluginService();
    private final PublishSubject<State> mConnectionState = PublishSubject.create();
    private final DevPluginResponseHandler mResponseHandler = new DevPluginResponseHandler();
    private final Handler mHandshakeTimeoutHandler = new Handler(Looper.getMainLooper());

    private volatile JsonSocket mSocket;

    public static DevPluginService getInstance() {
        return sInstance;
    }

    @AnyThread
    public boolean isConnected() {
        return mSocket != null && !mSocket.isClosed();
    }

    @AnyThread
    public boolean isDisconnected() {
        return mSocket == null || mSocket.isClosed();
    }

    @AnyThread
    public void disconnectIfNeeded() {
        if (isDisconnected())
            return;
        disconnect();
    }

    @AnyThread
    public void disconnect() {
        mSocket.close();
        mSocket = null;
    }

    public Observable<State> connectionState() {
        return mConnectionState;
    }

    @AnyThread
    public Observable<JsonSocket> connectToServer(String host) {
        int port = PORT;
        String ip = host;
        int i = host.lastIndexOf(':');
        if (i > 0 && i < host.length() - 1) {
            port = Integer.parseInt(host.substring(i + 1));
            ip = host.substring(0, i);
        }
        mConnectionState.onNext(new State(State.CONNECTING));
        return socket(ip, port)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnError(this::onSocketError);
    }

    @AnyThread
    private Observable<JsonSocket> socket(String ip, int port) {
        return Observable.fromCallable(() -> {
            JsonSocket socket = new JsonSocket(new Socket(ip, port));
            socket.data()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnComplete(() -> mConnectionState.onNext(new State(State.DISCONNECTED)))
                    .subscribe(data -> onSocketData(socket, data), this::onSocketError);
            sayHelloToServer(socket);
            return socket;
        })
                .subscribeOn(Schedulers.io());
    }

    @MainThread
    private void onSocketError(Throwable e) {
        e.printStackTrace();
        if (mSocket != null) {
            mConnectionState.onNext(new State(State.DISCONNECTED, e));
            mSocket.close();
            mSocket = null;
        }
    }

    @MainThread
    private void onSocketData(JsonSocket jsonSocket, JsonElement element) {
        if (!element.isJsonObject()) {
            Log.w(LOG_TAG, "onSocketData: not json object: " + element);
            return;
        }
        JsonObject obj = element.getAsJsonObject();
        JsonElement type = obj.get("type");
        if (type != null && type.isJsonPrimitive() && type.getAsString().equals(TYPE_HELLO)) {
            onServerHello(jsonSocket, obj);
            return;
        }
        mResponseHandler.handle(obj);
    }

    @WorkerThread
    private void sayHelloToServer(JsonSocket socket) throws IOException {
        writeMap(socket, TYPE_HELLO, new MapEntries<String, Object>()
                .entry("device_name", Build.BRAND + " " + Build.MODEL)
                .entry("client_version", CLIENT_VERSION)
                .entry("app_version", BuildConfig.VERSION_NAME)
                .entry("app_version_code", BuildConfig.VERSION_CODE)
                .map());
        mHandshakeTimeoutHandler.postDelayed(() -> {
            if (mSocket != socket && !socket.isClosed()) {
                onHandshakeTimeout(socket);
            }
        }, HANDSHAKE_TIMEOUT);
    }

    @MainThread
    private void onHandshakeTimeout(JsonSocket socket) {
        Log.i(LOG_TAG, "onHandshakeTimeout");
        mConnectionState.onNext(new State(State.DISCONNECTED, new SocketTimeoutException("handshake timeout")));
        socket.close();
    }

    @MainThread
    private void onServerHello(JsonSocket jsonSocket, JsonObject message) {
        Log.i(LOG_TAG, "onServerHello: " + message);
        mSocket = jsonSocket;
        mConnectionState.onNext(new State(State.CONNECTED));
    }

    @WorkerThread
    private static int write(JsonSocket socket, String type, JsonObject data) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.add("data", data);
        return socket.write(json);
    }

    @WorkerThread
    private static int writePair(JsonSocket socket, String type, Pair<String, String> pair) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty(pair.first, pair.second);
        return write(socket, type, data);
    }

    @WorkerThread
    private static int writeMap(JsonSocket socket, String type, Map<String, ?> map) throws IOException {
        JsonObject data = new JsonObject();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                data.addProperty(entry.getKey(), (String) value);
            } else if (value instanceof Character) {
                data.addProperty(entry.getKey(), (Character) value);
            } else if (value instanceof Number) {
                data.addProperty(entry.getKey(), (Number) value);
            } else if (value instanceof Boolean) {
                data.addProperty(entry.getKey(), (Boolean) value);
            } else if (value instanceof JsonElement) {
                data.add(entry.getKey(), (JsonElement) value);
            } else {
                throw new IllegalArgumentException("cannot put value " + value + " into json");
            }
        }
        return write(socket, type, data);
    }


    @SuppressLint("CheckResult")
    @AnyThread
    public void log(String log) {
        if (!isConnected())
            return;
        Observable.fromCallable(() ->
                writePair(mSocket, "log", new Pair<>("log", log)))
                .subscribeOn(Schedulers.io())
                .subscribe(EmptyObservers.consumer(), Throwable::printStackTrace);
    }
}