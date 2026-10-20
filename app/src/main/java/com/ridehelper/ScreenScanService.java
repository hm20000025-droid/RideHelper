
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
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ScreenScanService extends Service {

    private static final String CHANNEL_ID = "ridehelper_scan";
    private static final int NOTIFICATION_ID = 17;

    // شروط الرحلة - عدّلها حسب حاجتك
    private static final double MAX_PICKUP_KM = 3.0;
    private static final double MIN_PRICE_PER_KM = 3.0;

    // 0 تعني عدم تطبيق حد أدنى للسعر الإجمالي.
    // ضع هنا المبلغ الذي تريده بالدرهم، مثل 20.0 أو 30.0.
    private static final double MIN_TOTAL_PRICE = 0.0;

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
        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        floatingView = new TextView(this);
        floatingView.setText("RH\nبانتظار بيانات الرحلة");
        floatingView.setTextColor(Color.WHITE);
        floatingView.setTextSize(13);
        floatingView.setGravity(Gravity.CENTER);
        floatingView.setMaxWidth(320);
        floatingView.setPadding(18, 14, 18, 14);
        floatingView.setBackgroundColor(Color.DKGRAY);

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

        android.view.WindowManager wm =
                (android.view.WindowManager)
                        getSystemService(WINDOW_SERVICE);

        wm.getDefaultDisplay().getRealMetrics(metrics);

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
        if (!processing.compareAndSet(false, true)) {
            Image skipped = null;
            try {
                skipped = reader.acquireLatestImage();
            } catch (Exception ignored) {
            } finally {
                if (skipped != null) skipped.close();
            }
            return;
        }

        Image image = null;
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
            int rowPadding =
                    rowStride - pixelStride * screenWidth;

            Bitmap paddedBitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888);

            paddedBitmap.copyPixelsFromBuffer(buffer);

            bitmap = Bitmap.createBitmap(
                    paddedBitmap, 0, 0,
                    screenWidth, screenHeight);

            paddedBitmap.recycle();

        } catch (Exception e) {
            if (bitmap != null) bitmap.recycle();
            processing.set(false);
        } finally {
            if (image != null) image.close();
        }

        if (bitmap == null) return;

        final Bitmap finalBitmap = bitmap;

        try {
            recognizer.process(InputImage.fromBitmap(finalBitmap, 0))
                    .addOnSuccessListener(result -> {
                        String text = result.getText();

                        if (text == null) text = "";

                        // لا نصدر حكمًا قبل استخراج البيانات اللازمة.
                        evaluateTrip(text);
                    })
                    .addOnFailureListener(e ->
                            showStatus(
                                    "تعذرت قراءة الشاشة\nحاول مجددًا",
                                    Color.DKGRAY))
                    .addOnCompleteListener(task -> {
                        finalBitmap.recycle();
                        processing.set(false);
                    });
        } catch (Exception e) {
            finalBitmap.recycle();
            processing.set(false);
            showStatus("خطأ في تحليل الصورة", Color.DKGRAY);
        }
    }

    private void evaluateTrip(String text) {
        if (text.trim().isEmpty()) {
            showStatus("جارٍ التحليل\nلم يُقرأ أي نص",
                    Color.DKGRAY);
            return;
        }

        Double price = extractPrice(text);
        Double pickupKm = extractDistance(
                text,
                "(?:المسافة\\s*(?:إلى|لدى|للوصول إلى)\\s*الزبون"
                + "|المسافة\\s*إلى\\s*الراكب"
                + "|مسافة\\s*الالتقاط"
                + "|pickup\\s*distance"
                + "|distance\\s*to\\s*(?:pickup|rider))");

        Double tripKm = extractDistance(
                text,
                "(?:مسافة\\s*الرحلة"
                + "|المسافة\\s*الإجمالية"
                + "|مسافة\\s*الوصول"
                + "|trip\\s*distance"
                + "|total\\s*distance"
                + "|distance\\s*of\\s*trip)");

        // لا يمكن تقييم الرحلة إذا كانت أي قيمة أساسية مفقودة.
        if (price == null || pickupKm == null || tripKm == null
                || price <= 0 || pickupKm < 0 || tripKm <= 0) {

            showStatus(
                    "غير معروف - لا تتخذ قرارًا\n"
                    + "السعر: " + display(price)
                    + "\nمسافة الزبون: " + display(pickupKm)
                    + "\nمسافة الرحلة: " + display(tripKm),
                    Color.DKGRAY);
            return;
        }

        double pricePerKm = price / tripKm;

        boolean pickupOk = pickupKm <= MAX_PICKUP_KM;
        boolean rateOk = pricePerKm >= MIN_PRICE_PER_KM;
        boolean totalOk = MIN_TOTAL_PRICE <= 0
                || price >= MIN_TOTAL_PRICE;

        if (pickupOk && rateOk && totalOk) {
            showStatus(
                    "🟢 رحلة مناسبة"
                    + "\nالسعر: " + price + " درهم"
                    + "\nمسافة الزبون: " + pickupKm + " كم"
                    + "\nمسافة الرحلة: " + tripKm + " كم"
                    + "\nالسعر/كم: "
                    + String.format(Locale.US, "%.2f", pricePerKm),
                    Color.rgb(20, 125, 55));
        } else {
            String reason = !pickupOk
                    ? "الزبون أبعد من الحد المحدد"
                    : !rateOk
                    ? "السعر لكل كيلومتر منخفض"
                    : "السعر الإجمالي أقل من الحد";

            showStatus(
                    "🔴 رحلة غير مناسبة"
                    + "\n" + reason
                    + "\nالسعر: " + price + " درهم"
                    + "\nمسافة الزبون: " + pickupKm + " كم"
                    + "\nمسافة الرحلة: " + tripKm + " كم"
                    + "\nالسعر/كم: "
                    + String.format(Locale.US, "%.2f", pricePerKm),
                    Color.rgb(170, 25, 25));
        }
    }

    private Double extractPrice(String text) {
        // يقبل أمثلة مثل: 25 درهم، MAD 25، 25 DH.
        Pattern pattern = Pattern.compile(
                "(?i)(?:MAD|DH|درهم(?:ا|اً)?)\\s*"
                + "([0-9]+(?:[.,][0-9]+)?)"
                + "|"
                + "([0-9]+(?:[.,][0-9]+)?)\\s*"
                + "(?:MAD|DH|درهم(?:ا|اً)?)");

        Matcher matcher = pattern.matcher(text);

        if (!matcher.find()) return null;

        String value = matcher.group(1) != null
                ? matcher.group(1) : matcher.group(2);

        return parseNumber(value);
    }

    private Double extractDistance(
            String text, String labelPattern) {

        // يجب أن تظهر تسمية المسافة قبل الرقم.
        // لا نعتبر أي رقم متبوع بـ km مسافةً مؤكدة من تلقاء أنفسنا.
        Pattern pattern = Pattern.compile(
                "(?is)" + labelPattern
                + "\\s*[:：-]?\\s*"
                + "[^0-9\\n]{0,35}?"
                + "([0-9]+(?:[.,][0-9]+)?)\\s*"
                + "(?:km|kms|kilometers?|كم|كلم)\\b?");

        Matcher matcher = pattern.matcher(text);

        if (!matcher.find()) return null;

        return parseNumber(matcher.group(1));
    }

    private Double parseNumber(String value) {
        if (value == null) return null;

        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String display(Double value) {
        return value == null ? "غير مقروء" : value.toString();
    }

    private void showStatus(String message, int color) {
        if (floatingView == null) return;

        floatingView.post(() -> {
            if (floatingView != null) {
                floatingView.setText(message);
                floatingView.setBackgroundColor(color);
            }
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
            floatingView = null;
        }

        if (recognizer != null) {
         recognizer.close();
            recognizer = null;
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
