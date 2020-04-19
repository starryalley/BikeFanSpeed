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
    private static final String TAG = "BikeSpeedService";
    private static final int ONGOING_NOTIFICATION_ID = 9999;
    private static final String CHANNEL_DEFAULT_IMPORTANCE = "bike_fan_speed_channel";

    AntPlusBikeSpeedDistancePcc bsdPcc = null;
    PccReleaseHandle<AntPlusBikeSpeedDistancePcc> bsdReleaseHandle = null;

    // 700x23c circumference in meter
    private static final BigDecimal circumference = new BigDecimal(2.095);
    // m/s to km/h ratio
    private static final BigDecimal msToKmSRatio = new BigDecimal(3.6);

    // bike speed threshhold
    private static final float speedThreadLow = 10.0f;
    private static final float speedThreadHigh = 24.0f;

    private AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc> mResultReceiver = new AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikeSpeedDistancePcc>() {
        @Override
        public void onResultReceived(AntPlusBikeSpeedDistancePcc result,
                                     RequestAccessResult resultCode, DeviceState initialDeviceState) {
            switch (resultCode) {
                case SUCCESS:
                    bsdPcc = result;
                    Log.d(TAG, result.getDeviceName() + ": " + initialDeviceState);
                    // send broadcast
                    Intent i = new Intent("idv.markkuo.bikefanspeed.ANTDATA");
                    i.putExtra("service_status", initialDeviceState.toString());
                    sendBroadcast(i);

                    subscribeToEvents();
                    break;
                default:
                    Log.e(TAG,  " error:" + initialDeviceState + ", resultCode" + resultCode);
            }
        }

        private void subscribeToEvents() {
            bsdPcc.subscribeCalculatedSpeedEvent(new AntPlusBikeSpeedDistancePcc.CalculatedSpeedReceiver(circumference) {
                @Override
                public void onNewCalculatedSpeed(final long estTimestamp,
                                                 final EnumSet<EventFlag> eventFlags, final BigDecimal calculatedSpeed) {
                    // convert m/s to km/h
                    float speed = calculatedSpeed.multiply(msToKmSRatio).floatValue();
                    Log.d(TAG, "Speed:" + speed);
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
        //TODO do something useful
        Log.d(TAG, "Service onStartCommand");
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "Service started");
        super.onCreate();

        initAntPlus();

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
                        .setContentTitle("Bike FanSpeed control")
                        .setContentText("Active")
                        //.setSmallIcon(R.drawable.icon)
                        .setContentIntent(pendingIntent)
                        .setTicker("Bike FanSpeed")
                        .build();
        // start this service as a foreground one
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved called");
        super.onTaskRemoved(rootIntent);
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
    }

    // last speed
    private FanSpeed lastSpeed = FanSpeed.FAN_STOP;

    // fan speed supported
    public enum FanSpeed {
        FAN_STOP,
        FAN_1,
        FAN_2,
    }
    // the async task to set fan speed by requesting a private http endpoint
    private final class FanSpeedTask extends AsyncTask<Float, Void, Void> {
        private static final String uri = "http://192.168.1.201:8080/servo?pin=11&pos=";

        @Override
        protected Void doInBackground(Float... speed) {
            if (speed[0] < speedThreadLow) {
                setFanSpeed(FanSpeed.FAN_STOP);
            } else if (speed[0] < speedThreadHigh) {
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
                        } catch (Exception e) {
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
