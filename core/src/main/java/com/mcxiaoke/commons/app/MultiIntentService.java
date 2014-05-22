package com.mcxiaoke.commons.app;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import com.mcxiaoke.commons.utils.LogUtils;
import com.mcxiaoke.commons.utils.ThreadUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 类似于IntentService，但是多个异步任务可以并行执行
 * Service每隔300秒自动检查，如果活跃任务目为0则自动结束
 * 自动结束时间可设置，是否启用自动结束功能可设置
 * User: mcxiaoke
 * Date: 14-4-22 14-05-22
 * Time: 14:04
 */
public abstract class MultiIntentService extends Service {
    private static final String BASE_TAG = MultiIntentService.class.getSimpleName();

    // 默认空闲5分钟后自动stopSelf()
    public static final long AUTO_CLOSE_DEFAULT_TIME = 300 * 1000L;

    private final Object mLock = new Object();

    private ExecutorService mExecutor;
    private Handler mHandler;

    private volatile Map<Long, Future<?>> mFutures;
    private volatile AtomicInteger mRetainCount;

    private boolean mAutoCloseEnable;
    private long mAutoCloseTime;

    public MultiIntentService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtils.v(BASE_TAG, "onCreate()");
        mRetainCount = new AtomicInteger(0);
        mFutures = new ConcurrentHashMap<Long, Future<?>>();
        mAutoCloseEnable = true;
        mAutoCloseTime = AUTO_CLOSE_DEFAULT_TIME;
        ensureHandler();
        ensureExecutor();
        checkAutoClose();
    }

    @Override
    public final int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            dispatchIntent(intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        LogUtils.v(BASE_TAG, "onDestroy() mRetainCount=" + mRetainCount.get());
        LogUtils.v(BASE_TAG, "onDestroy() mFutures.size()=" + mFutures.size());
        cancelAutoClose();
        destroyHandler();
        destroyExecutor();
    }

    public void setAutoCloseEnable(boolean enable) {
        mAutoCloseEnable = enable;
        checkAutoClose();
    }

    public void setAutoCloseTime(long milliseconds) {
        mAutoCloseTime = milliseconds;
        checkAutoClose();
    }

    private void dispatchIntent(final Intent intent) {
        final long id = System.currentTimeMillis();
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                LogUtils.v(BASE_TAG, "dispatchIntent thread=" + Thread.currentThread());
                LogUtils.v(BASE_TAG, "dispatchIntent start id=" + id);
                onHandleIntent(intent, id);
                LogUtils.v(BASE_TAG, "dispatchIntent end id=" + id);
                release(id);
            }
        };
        Future<?> future = submit(runnable);
        retain(id, future);
    }

    private void retain(final long id, final Future<?> future) {
        LogUtils.v(BASE_TAG, "retain() id=" + id);
        mRetainCount.incrementAndGet();
        mFutures.put(id, future);
    }

    private void release(final long id) {
        LogUtils.v(BASE_TAG, "release() id=" + id);
        mRetainCount.decrementAndGet();
        mFutures.remove(id);
        checkAutoClose();
    }

    private final Runnable mAutoCloseRunnable = new Runnable() {
        @Override
        public void run() {
            autoClose();
        }
    };

    private void checkAutoClose() {
        if (mAutoCloseEnable) {
            scheduleAutoClose();
        } else {
            cancelAutoClose();
        }
    }

    private void scheduleAutoClose() {
        if (mAutoCloseTime > 0) {
            LogUtils.v(BASE_TAG, "scheduleAutoClose()");
            if (mHandler != null) {
                mHandler.postDelayed(mAutoCloseRunnable, mAutoCloseTime);
            }
        }
    }

    private void cancelAutoClose() {
        LogUtils.v(BASE_TAG, "cancelAutoClose()");
        if (mHandler != null) {
            mHandler.removeCallbacks(mAutoCloseRunnable);
        }
    }

    private void autoClose() {
        LogUtils.v(BASE_TAG, "autoClose() mRetainCount=" + mRetainCount.get());
        LogUtils.v(BASE_TAG, "autoClose() mFutures.size()=" + mFutures.size());
        if (mRetainCount.get() <= 0) {
            stopSelf();
        }
    }

    private void ensureHandler() {
        if (mHandler == null) {
            mHandler = new Handler();
        }
    }

    private void destroyHandler() {
        synchronized (mLock) {
            if (mHandler != null) {
                mHandler.removeCallbacksAndMessages(null);
                mHandler = null;
            }
        }
    }

    private ExecutorService ensureExecutor() {
        if (mExecutor == null || mExecutor.isShutdown()) {
            mExecutor = createExecutor();
        }
        return mExecutor;
    }

    private void destroyExecutor() {
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
    }

    protected final void cancel(long id) {
        Future<?> future = mFutures.get(id);
        if (future != null) {
            future.cancel(true);
            release(id);
        }
    }

    private Future<?> submit(Runnable runnable) {
        ensureExecutor();
        return mExecutor.submit(runnable);
    }

    protected ExecutorService createExecutor() {
        return ThreadUtils.newCachedThreadPool(BASE_TAG);
    }

    /**
     * 此方法在非UI线程执行
     *
     * @param intent Intent
     * @param id     ID，可以用于取消任务
     */
    protected abstract void onHandleIntent(Intent intent, long id);

}
