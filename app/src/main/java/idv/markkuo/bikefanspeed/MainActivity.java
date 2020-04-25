package idv.markkuo.bikefanspeed;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private TextView tv_sensorState;
    private TextView tv_timestamp, tv_speed, tv_fanspeed;
    private Button btn_service;

    private boolean serviceStarted = false;
    private MainActivityReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tv_sensorState = findViewById(R.id.SensorStateText);
        tv_speed = findViewById(R.id.SpeedText);
        tv_fanspeed = findViewById(R.id.FanSpeedText);
        tv_timestamp = findViewById(R.id.TimestampText);
        btn_service = findViewById(R.id.ServiceButton);

        if (isServiceRunning()) {
            Log.w(TAG, "Service already started");
            serviceStarted = true;
        }

        btn_service.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent i = new Intent(getApplicationContext(), BikeSpeedService.class);
                if (!serviceStarted) {
                    Log.d(TAG, "Starting Service");
                    MainActivity.this.startForegroundService(i);
                } else {
                    Log.d(TAG, "Stopping Service");
                    MainActivity.this.stopService(i);
                }
                serviceStarted = !serviceStarted;
                updateButtonState();
            }
        });

        // register intent from our service
        receiver = new MainActivityReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.bikefanspeed.ANTDATA");
        registerReceiver(receiver, filter);
    }

    private void updateButtonState() {
        // update the title
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                resetUi();
                if (serviceStarted)
                    btn_service.setText(getText(R.string.stop_service));
                else
                    btn_service.setText(getText(R.string.start_service));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        serviceStarted = isServiceRunning();
        updateButtonState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(receiver);
    }

    private void resetUi() {
        tv_sensorState.setText(getText(R.string.please_start));
        tv_fanspeed.setText(getText(R.string.fan_stopped));
        tv_speed.setText(getText(R.string.no_data));
        tv_timestamp.setText(getText(R.string.no_data));
    }

    private boolean isServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BikeSpeedService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private class MainActivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String statusString = intent.getStringExtra("service_status");
            final BikeSpeedService.FanSpeed fanSpeed = (BikeSpeedService.FanSpeed) intent.getSerializableExtra("fan_speed");
            final float speed = intent.getFloatExtra("speed", -1.0f);
            final long timestamp = intent.getLongExtra("timestamp", -1);

            runOnUiThread(new Runnable() {
                @SuppressLint("DefaultLocale")
                @Override
                public void run() {
                    if (statusString != null)
                        tv_sensorState.setText(statusString);
                    if (fanSpeed != null) {
                        switch (fanSpeed) {
                            case FAN_STOP:
                                tv_fanspeed.setText(getText(R.string.fan_stopped));
                                break;
                            case FAN_1:
                                tv_fanspeed.setText(getText(R.string.fan_low));
                                break;
                            case FAN_2:
                                tv_fanspeed.setText(getText(R.string.fan_high));
                                break;
                        }
                    }
                    if (speed >= 0.0f)
                        tv_speed.setText(String.format("%.02f", speed));
                    if (timestamp >= 0)
                        tv_timestamp.setText(String.valueOf(timestamp));
                }
            });
        }
    }
}
