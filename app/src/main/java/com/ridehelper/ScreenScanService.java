
package com.ridehelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenScanService extends Service {

    private static final String CHANNEL_ID = "ridehelper_scan";
    private static final int NOTIFICATION_ID = 17;

    private WindowManager windowManager;
    private TextView floatingView;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread scanThread;
    private Handler scanHandler;
    private TextRecognizer recognizer;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    private int screenWidth;
    private int screenHeight;
    private int screenDensity;

    private final MediaProjection.Callback projectionCallback =
            new MediaProjection.Callback() {
        @Override
        public void onStop() {
            stopSelf();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        recognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);

        scanThread = new HandlerThread("RideHelperOCR");
        scanThread.start();
        scanHandler = new Handler(scanThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || projection != null) {
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }

        int resultCode = intent.getIntExtra("resultCode", 0);

        Intent captureData;
        if (Build.VERSION.SDK_INT >= 33) {
            captureData = intent.getParcelableExtra(
                    "captureData", Intent.class);
        } else {
            captureData = intent.getParcelableExtra("captureData");
        }

        if (captureData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(
                    resultCode, captureData);

            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            projection.registerCallback(projectionCallback, scanHandler);

            if (!Settings.canDrawOverlays(this)) {
                stopSelf();
                return START_NOT_STICKY;
            }

            createFloatingView();
            startScreenCapture();

        } catch (Exception e) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void createFloatingView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        floatingView = new TextView(this);
        floatingView.setText("RH\nجاري قراءة الشاشة...");
        floatingView.setTextColor(Color.WHITE);
        floatingView.setTextSize(12);
        floatingView.setGravity(Gravity.CENTER);
        floatingView.setMaxWidth(300);
        floatingView.setBackgroundColor(Color.rgb(70, 70, 70));
        floatingView.setPadding(14, 10, 14, 10);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.END;
        params.x = 20;
        params.y = 180;

        windowManager.addView(floatingView, params);
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = new DisplayMetrics();

        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowManager wm =
                    (android.view.WindowManager)
                            getSystemService(WINDOW_SERVICE);
            android.view.Display display = wm.getDefaultDisplay();
            display.getRealMetrics(metrics);
        } else {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        }

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2);

        imageReader.setOnImageAvailableListener(
                this::readScreenImage, scanHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "RideHelperScreen",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                scanHandler);
    }

    private void readScreenImage(ImageReader reader) {
        Image image = null;

        if (!processing.compareAndSet(false, true)) {
            try {
                image = reader.acquireLatestImage();
                if (image != null) image.close();
            } catch (Exception ignored) {
            }
            return;
        }

        Bitmap bitmap = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                processing.set(false);
                return;
            }

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap paddedBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888);

            paddedBitmap.copyPixelsFromBuffer(buffer);

            bitmap = Bitmap.createBitmap(
                    paddedBitmap, 0, 0, screenWidth, screenHeight);

            paddedBitmap.recycle();

        } catch (Exception e) {
            processing.set(false);
        } finally {
            if (image != null) image.close();
        }

        if (bitmap == null) return;

        Bitmap finalBitmap = bitmap;

        recognizer.process(InputImage.fromBitmap(finalBitmap, 0))
                .addOnSuccessListener(result -> {
                    String text = result.getText().trim();

                    if (floatingView != null) {
                        String displayText;

                        if (text.isEmpty()) {
                            displayText = "RH\nلم يتم العثور على نص";
                        } else {
                            if (text.length() > 180) {
                                text = text.substring(0, 180);
                            }
                            displayText = "RH\n" + text;
                        }

                        String finalText = displayText;

                        floatingView.post(() -> {
                            if (floatingView != null) {
                                floatingView.setText(finalText);
                                floatingView.setBackgroundColor(
                                        Color.rgb(60, 60, 60));
                            }
                        });
                    }
                })
                .addOnFailureListener(e -> {
                    if (floatingView != null) {
                        floatingView.post(() ->
                                floatingView.setText("RH\nخطأ في قراءة النص"));
                    }
                })
                .addOnCompleteListener(task -> {
                    finalBitmap.recycle();
                    processing.set(false);
                });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "RideHelper screen reading",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Notification.Builder builder;

        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
                .setContentTitle("RideHelper")
                .setContentText("قراءة الشاشة قيد التشغيل")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) {
            }
        }

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            projection.stop();
            projection = null;
        }

        if (windowManager != null && floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) {
            }
        }

        if (recognizer != null) {
            recognizer.close();
        }

        if (scanThread != null) {
            scanThread.quitSafely();
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
