package idv.markkuo.bikefanspeed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

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

    // Ant+ sensors
    private AntPlusBikeSpeedDistancePcc bsdPcc = null;
    private PccReleaseHandle<AntPlusBikeSpeedDistancePcc> bsdReleaseHandle = null;

    // 700x23c circumference in meter
    private static final BigDecimal CIRCUMFERENCE = new BigDecimal(2.095);
    // m/s to km/h ratio
    private static final BigDecimal MS_TO_KMS_RATIO = new BigDecimal(3.6);

    // bike speed threshold
    private static final float SPEED_THRESHOLD_LOW = 3.0f;
    private static final float SPEED_THRESHOLD_HIGH = 10.0f;

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> mResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>() {
        @Override
        public void onResultReceived(AntPlusBikeSpeedDistancePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            if (resultCode == RequestAccessResult.SUCCESS) {
                bsdPcc = result;
                Log.i(TAG, result.getDeviceName() + ": " + initialDeviceState);
                subscribeToEvents();
            } else {
                Log.w(TAG, "state changed:" + initialDeviceState + ", resultCode:" + resultCode);
            }
            // send broadcast
            Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
            i.putExtra("service_status", initialDeviceState.toString());
            sendBroadcast(i);

        }

        private void subscribeToEvents() {
            bsdPcc.subscribeCalculatedSpeedEvent(new AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(CIRCUMFERENCE) {
                @Override
                public void onNewCalculatedSpeed(final long estTimestamp,
                                                 final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedSpeed) {
                    // convert m/s to km/h
                    float speed = calculatedSpeed.multiply(MS_TO_KMS_RATIO).floatValue();
                    Log.v(TAG, "Speed:" + speed);
                    // update fan speed according to this speed
                    new FanSpeedTask().execute(speed);

                    // send broadcast
                    Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                    i.putExtra("speed", speed);
                    i.putExtra("timestamp", estTimestamp);
                    sendBroadcast(i);
                }
            });

        }
    };

    // Receives state changes and shows it on the status display line
    private AntPluginPcc.IDeviceStateChangeReceiver mDeviceStateChangeReceiver = new AntPluginPcc.IDeviceStateChangeReceiver() {
        @Override
        public void onDeviceStateChange(final DeviceState newDeviceState) {
            Log.d(TAG, bsdPcc.getDeviceName() + " onDeviceStateChange:" + newDeviceState);
            // send broadcast
            Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
            i.putExtra("service_status", newDeviceState.name());
            sendBroadcast(i);

            // if the device is dead (closed)
            if (newDeviceState == DeviceState.DEAD) {
                bsdPcc = null;
                // stop fan
                new FanSpeedTask().execute(0f);
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        super.onCreate();

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
        if(bsdReleaseHandle != null)
            bsdReleaseHandle.close();
    }

    private void initAntPlus() {
        //Release the old access if it exists
        if(bsdReleaseHandle != null)
            bsdReleaseHandle.close();

        Log.d(TAG, "requesting ANT+ access");
        // starts speed sensor search
        bsdReleaseHandle = AntPlusBikeSpeedDistancePcc.requestAccess(this, 0, 0, false,
                mResultReceiver, mDeviceStateChangeReceiver);

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
    private final class FanSpeedTask extends AsyncTask<Float, Void, Void> {
        private static final String uri = "http://192.168.1.201:8080/servo?pin=11&pos=";

        @Override
        protected Void doInBackground(Float... speed) {
            if (speed[0] < SPEED_THRESHOLD_LOW) {
                setFanSpeed(FanSpeed.FAN_STOP);
            } else if (speed[0] < SPEED_THRESHOLD_HIGH) {
                setFanSpeed(FanSpeed.FAN_1);
            } else {
                setFanSpeed(FanSpeed.FAN_2);
            }
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
