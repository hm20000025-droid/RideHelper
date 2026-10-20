
package com.ridehelper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_OVERLAY = 1002;
    private MediaProjectionManager projectionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        projectionManager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);

        TextView title = new TextView(this);
        title.setText("RideHelper");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        TextView info = new TextView(this);
        info.setText("مساعد تحليل الرحلات\n\n" +
                "المرحلة التجريبية\n" +
                "سنضيف قراءة الشاشة وتحليل العروض.");
        info.setTextSize(18);
        info.setGravity(Gravity.CENTER);
        info.setPadding(0, 24, 0, 24);

        Button startButton = new Button(this);
        startButton.setText("السماح بقراءة الشاشة");
        startButton.setOnClickListener(v -> startCaptureSetup());

        layout.addView(title);
        layout.addView(info);
        layout.addView(startButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(layout);
    }

    private void startCaptureSetup() {
        if (!Settings.canDrawOverlays(this)) {
            Intent overlayIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(overlayIntent, REQUEST_OVERLAY);
            return;
        }

        requestScreenCapture();
    }

    private void requestScreenCapture() {
        startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) {
                requestScreenCapture();
            }
            return;
        }

        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent =
                        new Intent(this, ScreenScanService.class);
                serviceIntent.putExtra("resultCode", resultCode);
                serviceIntent.putExtra("captureData", data);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                finish();
            }
        }
    }
}
