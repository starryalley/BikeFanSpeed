package idv.markkuo.bikefanspeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc;
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikeSpeedDistancePcc;
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState;
import com.dsi.ant.plugins.antplus.pcc.defines.EventFlag;
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult;
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc;
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.EnumSet;

public class BikeSpeedService extends Service {
    private static final String TAG = BikeSpeedService.class.getSimpleName();
    private static final int ONGOING_NOTIFICATION_ID = 8888;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "bike_fan_speed_channel";

    // Ant+ speed sensor
    private AntPlusBikeSpeedDistancePcc bsdPcc = null;
    private PccReleaseHandle<AntPlusBikeSpeedDistancePcc> bsdReleaseHandle = null;

    // Ant+ power sensor
    private AntPlusBikePowerPcc pwrPcc = null;
    private PccReleaseHandle<AntPlusBikePowerPcc> bpReleaseHandle = null;

    // 700x23c circumference in meter
    private static final BigDecimal CIRCUMFERENCE = new BigDecimal(2.095);
    // m/s to km/h ratio
    private static final BigDecimal MS_TO_KMS_RATIO = new BigDecimal(3.6);

    // bike speed threshold
    private static final float SPEED_THRESHOLD_LOW = 3.0f;
    private static final float SPEED_THRESHOLD_HIGH = 10.0f;
    // bike power threshold
    private static final float POWER_THRESHOLD_LOW = 80.0f;
    private static final float POWER_THRESHOLD_HIGH = 180.0f;

    private boolean manualFanControl = false;
    private boolean usePower = false;

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikePowerPcc> mPowerResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikePowerPcc>() {
        @Override
        public void onResultReceived(AntPlusBikePowerPcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                pwrPcc = result;
                Log.i(TAG, "[Power]" + result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else {
                Log.w(TAG, "power sensor state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            if (usePower) {
                Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                i.putExtra("service_status", initialDeviceState.toString());
                sendBroadcast(i);
            }
        }

        private void subscribeToEvents() {
            pwrPcc.subscribeCalculatedPowerEvent(new AntPlusBikePowerPcc.ICalculatedPowerReceiver() {
                @Override
                public void onNewCalculatedPower(
                        final long estTimestamp, final EnumSet<EventFlag> eventFlags,
                        final AntPlusBikePowerPcc.DataSource dataSource,
                        final BigDecimal calculatedPower) {
                    float power = calculatedPower.floatValue();
                    Log.v(TAG, "Power:" + power);
                    // send broadcast
                    if (usePower) {
                        // update fan speed according to this speed
                        if (!manualFanControl) {
                            if (power < POWER_THRESHOLD_LOW) {
                                new FanSpeedTask().execute(FanSpeed.FAN_STOP);
                            } else if (power < POWER_THRESHOLD_HIGH) {
                                new FanSpeedTask().execute(FanSpeed.FAN_1);
                            } else {
                                new FanSpeedTask().execute(FanSpeed.FAN_2);
                            }
                        }
                        Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                        i.putExtra("speed", calculatedPower.floatValue());
                        i.putExtra("timestamp", estTimestamp);
                        sendBroadcast(i);
                    }
                }
            });
        }
    };

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> mSpeedResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>() {
        @Override
        public void onResultReceived(AntPlusBikeSpeedDistancePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                bsdPcc = result;
                Log.i(TAG, "[Speed]" + result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else {
                Log.w(TAG, "speed sensor state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            if (!usePower) {
                Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                i.putExtra("service_status", initialDeviceState.toString());
                sendBroadcast(i);
            }
        }

        private void subscribeToEvents() {
            bsdPcc.subscribeCalculatedSpeedEvent(new AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(CIRCUMFERENCE) {
                @Override
                public void onNewCalculatedSpeed(final long estTimestamp,
                                                 final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedSpeed) {
                    // convert m/s to km/h
                    float speed = calculatedSpeed.multiply(MS_TO_KMS_RATIO).floatValue();
                    Log.v(TAG, "Speed:" + speed);
                    if (!usePower) {
                        // update fan speed according to this speed
                        if (!manualFanControl) {
                            if (speed < SPEED_THRESHOLD_LOW) {
                                new FanSpeedTask().execute(FanSpeed.FAN_STOP);
                            } else if (speed < SPEED_THRESHOLD_HIGH) {
                                new FanSpeedTask().execute(FanSpeed.FAN_1);
                            } else {
                                new FanSpeedTask().execute(FanSpeed.FAN_2);
                            }
                        }
                        // send broadcast
                        Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                        i.putExtra("speed", speed);
                        i.putExtra("timestamp", estTimestamp);
                        sendBroadcast(i);
                    }
                }
            });

        }
    };

    // Receives state changes and shows it on the status display line
    private AntPluginPcc.IDeviceStateChangeReceiver mSpeedDeviceStateChangeReceiver = new AntPluginPcc.IDeviceStateChangeReceiver() {
        @Override
        public void onDeviceStateChange(final DeviceState newDeviceState) {
            Log.d(TAG, bsdPcc.getDeviceName() + " onDeviceStateChange:" + newDeviceState);
            // send broadcast
            if (!usePower) {
                Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                i.putExtra("service_status", newDeviceState.name());
                sendBroadcast(i);
            }

            // if the device is dead (closed)
            if (newDeviceState == DeviceState.DEAD) {
                bsdPcc = null;
                // stop fan
                if (!usePower && !manualFanControl)
                    new FanSpeedTask().execute(FanSpeed.FAN_STOP);
            }
        }
    };

    private AntPluginPcc.IDeviceStateChangeReceiver mPowerDeviceStateChangeReceiver = new AntPluginPcc.IDeviceStateChangeReceiver() {
        @Override
        public void onDeviceStateChange(final DeviceState newDeviceState) {
            Log.d(TAG, pwrPcc.getDeviceName() + " onDeviceStateChange:" + newDeviceState);
            // send broadcast
            if (usePower) {
                Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                i.putExtra("service_status", newDeviceState.name());
                sendBroadcast(i);
            }

            // if the device is dead (closed)
            if (newDeviceState == DeviceState.DEAD) {
                pwrPcc = null;
                // stop fan
                if (usePower && !manualFanControl)
                    new FanSpeedTask().execute(FanSpeed.FAN_STOP);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        return Service.START_NOT_STICKY;
    }


    private class BikeSpeedServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.hasExtra("manual")) {
                manualFanControl = intent.getBooleanExtra("manual", false);
                Log.i(TAG, "Manual Fan Control:" + manualFanControl);
            }
            if (intent.hasExtra("use_power")) {
                usePower = intent.getBooleanExtra("use_power", false);
                Log.i(TAG, "Use Power:" + usePower);
            }
            if (intent.hasExtra("fanspeed")) {
                FanSpeed fanspeed = (FanSpeed) intent.getSerializableExtra("fanspeed");
                Log.d(TAG, "Got speed request:" + fanspeed);
                new FanSpeedTask().execute(fanspeed);
            }
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        super.onCreate();

        BikeSpeedServiceReceiver serviceReceiver = new BikeSpeedServiceReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("idv.markkuo.bikefanspeed.fanspeed");
        registerReceiver(serviceReceiver, filter);

        Intent notificationIntent = new Intent(this, BikeSpeedService.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, 0);

        // create notification channel
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(CHANNEL_DEFAULT_IMPORTANCE, CHANNEL_DEFAULT_IMPORTANCE, importance);
        channel.setDescription(CHANNEL_DEFAULT_IMPORTANCE);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        assert notificationManager != null;
        notificationManager.createNotificationChannel(channel);
        // build a notification
        Notification notification =
                new Notification.Builder(this, CHANNEL_DEFAULT_IMPORTANCE)
                        .setContentTitle(getText(R.string.app_name))
                        .setContentText("Active")
                        //.setSmallIcon(R.drawable.icon)
                        .setContentIntent(pendingIntent)
                        .setTicker(getText(R.string.app_name))
                        .build();
        // start this service as a foreground one
        startForeground(ONGOING_NOTIFICATION_ID, notification);

        initAntPlus();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called");
        super.onTaskRemoved(rootIntent);
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        super.onDestroy();
        cleanHandles();
    }

    private void cleanHandles() {
        //Release the old access if it exists
        if(bsdReleaseHandle != null)
            bsdReleaseHandle.close();
        if(bpReleaseHandle != null)
            bpReleaseHandle.close();
    }

    private void initAntPlus() {
        cleanHandles();

        Log.d(TAG, "requesting ANT+ access");
        // starts speed sensor search
        bsdReleaseHandle = AntPlusBikeSpeedDistancePcc.requestAccess(this, 0, 0, false,
                mSpeedResultReceiver, mSpeedDeviceStateChangeReceiver);
        bpReleaseHandle = AntPlusBikePowerPcc.requestAccess(this, 0, 0,
                mPowerResultReceiver, mPowerDeviceStateChangeReceiver);

        // send initial state for UI
        Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
        i.putExtra("service_status", "SEARCHING");
        sendBroadcast(i);
    }

    // last speed
    private FanSpeed lastSpeed = FanSpeed.FAN_STOP;

    // fan speed supported
    enum FanSpeed {
        FAN_STOP,
        FAN_1,
        FAN_2,
    }
    // the async task to set fan speed by requesting a private http endpoint
    private final class FanSpeedTask extends AsyncTask<FanSpeed, Void, Void> {
        private static final String uri = "http://192.168.1.201:8080/servo?pin=11&pos=";

        @Override
        protected Void doInBackground(FanSpeed... speed) {
            setFanSpeed(speed[0]);
            return null;
        }

        private void setFanSpeed(FanSpeed speed) {
            if (speed == lastSpeed)
                return;
            Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
            i.putExtra("fan_speed", speed);
            sendBroadcast(i);
            switch (speed) {
                case FAN_STOP:
                    Log.d(TAG, "Stop Fan");
                    if (lastSpeed == FanSpeed.FAN_2) {
                        // do not allow direct jump to stop
                        setFanSpeed(FanSpeed.FAN_1);
                        return;
                    }
                    if (lastSpeed == FanSpeed.FAN_1) {
                        // rewind a bit
                        setServoPosition(55);
                        try {
                            Thread.sleep(500);
                        } catch (Exception ignored) {
                        }
                    }
                    setServoPosition(10);
                    break;
                case FAN_1:
                    Log.d(TAG, "Fan Speed 1");
                    if (lastSpeed == FanSpeed.FAN_STOP)
                        setServoPosition(55);
                    else if (lastSpeed == FanSpeed.FAN_2)
                        setServoPosition(45);
                    break;
                case FAN_2:
                    if (lastSpeed == FanSpeed.FAN_STOP) {
                        // do not allow direct jump to FAN_2
                        setFanSpeed(FanSpeed.FAN_1);
                        return;
                    }
//                    if (lastSpeed == FanSpeed.FAN_1) {
//                        // rewind a bit
//                        setServoPosition(40);
//                        try {
//                            Thread.sleep(500);
//                        } catch (Exception e) {
//                        }
//                    }

                    Log.d(TAG, "Fan Speed 2");
                    setServoPosition(85);
                    break;
            }
            lastSpeed = speed;
        }

        private void setServoPosition(int position) {
            try {
                URL url = new URL(uri + position);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                if (con.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    Log.v(TAG, "Successfully setting Servo position:" + position);
                } else {
                    Log.e(TAG, "Error setting Servo position:" + position + ", Error:" + con.getResponseCode() + ", Reason:" + con.getContent().toString());
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
