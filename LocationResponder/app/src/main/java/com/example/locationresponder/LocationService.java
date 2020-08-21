package com.example.locationresponder;

import android.Manifest;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.Notification;
import android.bluetooth.BluetoothGattCallback;
import androidx.core.app.ActivityCompat;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothGattDescriptor;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import android.speech.tts.UtteranceProgressListener;

public class LocationService extends Service implements UIHandler.Callback {
    static final String CHANNEL_ID = "default";
    static final String NOTIFICATION_TITLE = "LocationResponder";
    static final int NOTIFICATION_ID = 1;
    static final int NOTIFICATION_ID2 = 2;
    static final int UIMSG_TOAST = 2001;
    static final int UIMSG_RECOGNIZE = 2002;
    static final int MinTime = 5000; // millis
    static final float MinDistance = 10; // meters
    static final UUID serviceUuid = UUID.fromString("08030900-7d3b-4ebf-94e9-18abc4cebede");
    static final UUID sendUuid = UUID.fromString("08030901-7d3b-4ebf-94e9-18abc4cebede");
    static final UUID readUuid = UUID.fromString("08030902-7d3b-4ebf-94e9-18abc4cebede");
    static final UUID noteUuid = UUID.fromString("08030903-7d3b-4ebf-94e9-18abc4cebede");
    static final UUID UUID_CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int UUID_VALUE_SIZE = 20;
    static final int DEFAULT_CONN_TIMEOUT = 10000;

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR = -1;
    public static final int STATUS_DISABLED = -2;
    public static final int CMD_LOCATION = 0x01;
    public static final int CMD_SPEECH = 0x02;
    public static final int CMD_HTTP_POST = 0x03;
    public static final int CMD_VIBRATOR = 0x04;
    public static final int CMD_NOTIFICATION = 0x05;
    public static final int CMD_TOAST = 0x06;
    public static final int CMD_RECOGNIZE = 0x07;
    public static final int CMD_BROWSER = 0x08;

    Context context;

    TextToSpeech tts;
    SpeechRecognizer recognizer;
    LocationManager locationManager;
    LocationListener locationListener;

    PendingIntent pendingIntent;
    NotificationManager notificationManager;
    Vibrator vibrator;

    BluetoothManager btManager;
    BluetoothAdapter btAdapter;
    BluetoothLeScanner mScanner;
    BluetoothGatt mConnGatt = null;
    BluetoothGattService mGattService = null;
    BluetoothGattCharacteristic charNote = null;
    BluetoothGattCharacteristic charRead = null;
    BluetoothGattCharacteristic charSend = null;
    BluetoothDevice mDevice;
    ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    PacketQueue queue;

    AudioManager audioManager;
    AudioFocusRequest mFocusRequest;

    BluetoothDevice recDevice;
    BluetoothHeadset mBluetoothHeadset;

    UIHandler handler;
    Location location = null;
    Location targetLocation = null;

    int g_cmd;
    int g_txid;

    public enum ConnectionState{
        Idle,
        Connecting,
        Connected
    }

    ConnectionState isConnected = ConnectionState.Idle;
    boolean isEnable = true;
    boolean isTtsReady = false;

    @Override
    public void onCreate() {
        super.onCreate();

        context = getApplicationContext();
        handler = new UIHandler(this);

        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if( status == TextToSpeech.SUCCESS ) {
                    audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    // ときどきフォーカス取得が遅れるので、setOnAudioFocusChangeListener() を使うのが望ましい
                    mFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .build();

                    tts.setOnUtteranceProgressListener(mTtsListener);

                    isTtsReady = true;
                }else{
                    Log.d(MainActivity.TAG, "TextToSpeech init error");
                }
            }
        });
        if( tts == null ){
            Log.e(MainActivity.TAG, "TextToSpeech not available");
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        if( recognizer == null ) {
            Log.e(MainActivity.TAG, "SpeechRecognizer not available");
        }else {
            initializeRecognizer();
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if( vibrator == null ) {
            Log.e(MainActivity.TAG, "Vibrator not available");
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if( locationManager == null ) {
            Log.e(MainActivity.TAG, "LocationManager not available");
            return;
        }

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if( notificationManager == null ) {
            Log.e(MainActivity.TAG, "NotificationManager not available");
            return;
        }else {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, NOTIFICATION_TITLE, NotificationManager.IMPORTANCE_DEFAULT);
            // 通知音を消さないと毎回通知音が出てしまう。この辺りの設定はcleanにしてから変更
            channel.setSound(null, null);
            channel.enableLights(false);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);

            Intent notifyIntent = new Intent(this, MainActivity.class);
            notifyIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        btManager = (BluetoothManager)getSystemService(Activity.BLUETOOTH_SERVICE);
        if( btManager == null ) {
            Log.e(MainActivity.TAG, "BluetoothManager not available");
            return;
        }else {
            btAdapter = btManager.getAdapter();
            if (btAdapter == null) {
                Log.e(MainActivity.TAG, "BluetoothAdapter not available");
                return;
            } else {
                btAdapter.getProfileProxy(this, mProfileListener, BluetoothProfile.HEADSET);
                mScanner = btAdapter.getBluetoothLeScanner();
                if (mScanner == null) {
                    Log.e(MainActivity.TAG, "BluetoothLeScanner not available");
                    return;
                }
            }
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        switch (message.what) {
            case UIHandler.MSG_ID_TEXT:{
                if( message.arg1 == UIMSG_TOAST ){
                    Toast.makeText(this, (String)message.obj, Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case UIHandler.MSG_ID_NONE:{
                if( message.arg1 == UIMSG_RECOGNIZE){
                    startRecognizer();
                }
                break;
            }
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(MainActivity.TAG, "onStartCommand");

        if( notificationManager != null ){
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("BLEデバイスをスキャン中")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pendingIntent)
                    .build();
            startForeground(NOTIFICATION_ID, notification);
        }

        startLocation();
        startBleScan();

        return START_NOT_STICKY;
    }

    private void startBleScan(){
        Log.d(MainActivity.TAG, "startBleScan");

        if( mScanner == null )
            return;

        ScanFilter scanFilter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(serviceUuid)).build();
        ArrayList scanFilterList = new ArrayList();
        scanFilterList.add(scanFilter);
        ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build();

        mScanner.startScan(scanFilterList, scanSettings, mScanCallback);
    }

    private final ScanCallback mScanCallback = new ScanCallback(){
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            Log.d(MainActivity.TAG, "onScanResult");

            BluetoothDevice device = result.getDevice();
            if( device == null )
                return;

            Log.d(MainActivity.TAG, "BLEデバイスを発見しました");
            for( BluetoothDevice dev : deviceList){
                Log.d(MainActivity.TAG, device.getAddress());
                if( dev.equals(device) )
                    return;
            }
            deviceList.add(device);
        }
    };

    void changeNotificationTitle(String title){
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    private final BluetoothGattCallback mGattcallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(MainActivity.TAG, "BLEデバイスと接続しました");
                if (mConnGatt != null)
                    mConnGatt.discoverServices();
            }else if( newState == BluetoothProfile.STATE_DISCONNECTED ){
                Log.d(MainActivity.TAG, "BLEデバイスと切断しました");
                isConnected = ConnectionState.Connecting;

                changeNotificationTitle("BLEデバイスに未接続");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(MainActivity.TAG, "onServicesDiscovered");

            try {
                mGattService = mConnGatt.getService(serviceUuid);

                charNote = mGattService.getCharacteristic(noteUuid);
                boolean result = mConnGatt.setCharacteristicNotification(charNote, true);
                if (!result) {
                    Log.e(MainActivity.TAG, "setCharacteristicNotification error");
                    return;
                }

                BluetoothGattDescriptor descriptor = charNote.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                result = mConnGatt.writeDescriptor(descriptor);
                if (!result) {
                    Log.e(MainActivity.TAG, "writeDescriptor error");
                    return;
                }

                charRead = mGattService.getCharacteristic(readUuid);
                charSend = mGattService.getCharacteristic(sendUuid);

                isConnected = ConnectionState.Connected;
                isEnable = true;
                queue = new PacketQueue(UUID_VALUE_SIZE, mConnGatt, charSend);

                Log.d(MainActivity.TAG, "BLEデバイスをディスカバリサービスしました");

                changeNotificationTitle("BLEデバイスに接続中");
            }catch(Exception ex){
                Log.e(MainActivity.TAG, ex.getMessage());
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(MainActivity.TAG, "onCharacteristicChange");

            if( characteristic.getUuid().compareTo(noteUuid) == 0)
                processIndication(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(MainActivity.TAG, "onCharacteristicWrite status=" + status);

            if( characteristic.getUuid().compareTo(sendUuid) == 0)
                queue.sendPacketNext();
        }

/*
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d(MainActivity.TAG, "onCharacteristicRead status=" + status);
        }
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(MainActivity.TAG, "onDescriptorWrite status=" + status);
        }
        @Override
        public void  onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            Log.d(MainActivity.TAG, "onMtuChanged status=" + status);
        }
*/
    };

    private static byte[] json2bin(JSONObject json) throws Exception{
        return json.toString().getBytes("UTF-8");
    }

    private void processIndication(byte[] data){
        Log.d(MainActivity.TAG, "processIndication");

        int received_len = queue.parsePacket(data);
        if( received_len > 0) {
            try{
                String received = new String(queue.g_recv_buffer, 0, received_len);
                JSONObject json = new JSONObject(received);
                processPacket(json);
            }catch(Exception ex){
                Log.e(MainActivity.TAG, ex.getMessage());
            }
        }
    }

    private void processPacket(final JSONObject json) throws Exception{
        g_cmd = json.getInt("cmd");
        g_txid = ( json.has("txid") ) ? json.getInt("txid") : 0;
        Log.d(MainActivity.TAG, "processPacket:" + g_cmd);

        if( queue.isProgress )
            return;

        if( !isEnable ){
            JSONObject result_json = new JSONObject();
            result_json.put("rsp", g_cmd);
            result_json.put("txid", g_txid);
            result_json.put("status", STATUS_DISABLED);
            queue.sendPacketStart(json2bin(result_json));

            return;
        }

        switch( g_cmd ){
            case CMD_BROWSER: {
                String url = json.getString("url");
                Uri uri = Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                break;
            }
            case CMD_RECOGNIZE: {
                handler.sendUIMessage(UIHandler.MSG_ID_NONE, UIMSG_RECOGNIZE, null);

                break;
            }
            case CMD_LOCATION:{
                JSONObject result_json = new JSONObject();
                result_json.put("rsp", g_cmd);
                result_json.put("txid", g_txid);
                if( location != null ) {
                    float speed = location.getSpeed() * 3.6f;
                    result_json.put("status", STATUS_OK);
                    result_json.put("latitude", location.getLatitude());
                    result_json.put("longitude", location.getLongitude());
                    result_json.put("speed", speed);
                    if( json.has("latitude") && json.has("longitude") ){
                        double base_latitude = json.getDouble("latitude");
                        double base_longitude = json.getDouble("longitude");
                        float[] distances = new float[3];
                        Location.distanceBetween(location.getLatitude(), location.getLongitude(), base_latitude, base_longitude, distances);
                        result_json.put("distance", distances[0]);
                        result_json.put("direction", distances[1]);
                    }else if( targetLocation != null ){
                        float[] distances = new float[3];
                        Location.distanceBetween(location.getLatitude(), location.getLongitude(), targetLocation.getLatitude(), targetLocation.getLongitude(), distances);
                        result_json.put("distance", distances[0]);
                        result_json.put("direction", distances[1]);
                    }
                }else{
                    result_json.put("status", STATUS_ERROR);
                }
                queue.sendPacketStart(json2bin(result_json));
                break;
            }
            case CMD_SPEECH: {
                String message = json.getString("message");
                speechTts(message);

                break;
            }
            case CMD_TOAST: {
                String message = json.getString("message");
                handler.sendUIMessage(UIHandler.MSG_ID_TEXT, UIMSG_TOAST, message);

                JSONObject result_json = new JSONObject();
                result_json.put("rsp", g_cmd);
                result_json.put("txid", g_txid);
                result_json.put("status", STATUS_OK);
                queue.sendPacketStart(json2bin(result_json));
                break;
            }
            case CMD_NOTIFICATION: {
                String message = json.getString("message");

                Intent notifyIntent = new Intent(this, MainActivity.class);
                notifyIntent.putExtra("clipboard", message);
                notifyIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT );

                Notification notification = new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(message)
                        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setWhen(System.currentTimeMillis())
                        .setContentIntent(pendingIntent)
                        .build();
                notificationManager.notify(NOTIFICATION_ID2, notification);

                JSONObject result_json = new JSONObject();
                result_json.put("rsp", g_cmd);
                result_json.put("txid", g_txid);
                result_json.put("status", STATUS_OK);
                queue.sendPacketStart(json2bin(result_json));

                break;
            }
            case CMD_HTTP_POST: {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject response = HttpPostJson.doPost(json.getString("url"), json.getJSONObject("request"), DEFAULT_CONN_TIMEOUT);

                            JSONObject result_json = new JSONObject();
                            result_json.put("rsp", g_cmd);
                            result_json.put("txid", g_txid);
                            result_json.put("response", response );
                            queue.sendPacketStart(json2bin(result_json));
                        }catch(Exception ex){
                            Log.e(MainActivity.TAG, ex.getMessage());
                            try {
                                JSONObject result_json = new JSONObject();
                                result_json.put("rsp", g_cmd);
                                result_json.put("txid", g_txid);
                                result_json.put("status", STATUS_ERROR);
                                queue.sendPacketStart(json2bin(result_json));
                            }catch(Exception ex2){
                                Log.e(MainActivity.TAG, ex2.getMessage());
                            }
                        }
                    }
                }).start();
                break;
            }
            case CMD_VIBRATOR: {
                int msec = ( json.has("duration") ) ? json.getInt("duration") : 500;
                vibrator.vibrate(VibrationEffect.createOneShot(msec, VibrationEffect.DEFAULT_AMPLITUDE));

                JSONObject result_json = new JSONObject();
                result_json.put("rsp", g_cmd);
                result_json.put("txid", g_txid);
                result_json.put("status", STATUS_OK);
                queue.sendPacketStart(json2bin(result_json));
                break;
            }
        }
    }

    void speechTts(String message){
        Log.d(MainActivity.TAG, "speechTts");

        if(tts == null || !isTtsReady)
            return;

        if( audioManager != null )
            audioManager.requestAudioFocus( mFocusRequest );

        tts.speak(message, TextToSpeech.QUEUE_ADD, null, TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID );
    }

    private final UtteranceProgressListener mTtsListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            Log.d(MainActivity.TAG,"progress on Start " + utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
            Log.d(MainActivity.TAG,"progress on Done " + utteranceId);
            if( audioManager != null )
                audioManager.abandonAudioFocusRequest(mFocusRequest);

            try {
                JSONObject result_json = new JSONObject();
                result_json.put("rsp", CMD_SPEECH);
                result_json.put("txid", g_txid);
                result_json.put("status", STATUS_OK);
                queue.sendPacketStart(json2bin(result_json));
            }catch(Exception ex){
                Log.e(MainActivity.TAG, ex.getMessage());
            }
        }

        @Override
        public void onError(String utteranceId) {
            Log.d(MainActivity.TAG,"progress on Error " + utteranceId);
            if( audioManager != null )
                audioManager.abandonAudioFocusRequest(mFocusRequest);

            try {
                JSONObject result_json = new JSONObject();
                result_json.put("rsp", CMD_SPEECH);
                result_json.put("txid", g_txid);
                result_json.put("status", STATUS_ERROR);
                queue.sendPacketStart(json2bin(result_json));
            }catch(Exception ex){
                Log.e(MainActivity.TAG, ex.getMessage());
            }
        }
    };

    private void startRecognizer() {
        Log.d(MainActivity.TAG, "startRecognizer" );

        if (recognizer == null)
            return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return;

        if( recDevice != null ) {
            boolean result = mBluetoothHeadset.startVoiceRecognition(recDevice);
            Log.d(MainActivity.TAG, "Result:" + result);
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        recognizer.startListening(intent);
    }

    private void initializeRecognizer(){
        Log.d(MainActivity.TAG, "initializeRecognizer" );

        recognizer.setRecognitionListener(new RecognitionListener() {
            boolean recognizeActive;

            public void	onReadyForSpeech(Bundle params) {
                Log.d(MainActivity.TAG, "onReadyForSpeech" );

                recognizeActive = true;
            }

            public void	onResults(Bundle results) {
                Log.d(MainActivity.TAG, "onResults" );

                if (!recognizeActive)
                    return;
                recognizeActive = false;

                if( recDevice != null )
                    mBluetoothHeadset.stopVoiceRecognition(recDevice);

                List<String> recData = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String message = "";
                if (recData.size() > 0) {
                    message = recData.get(0);
                    Log.d(MainActivity.TAG, "Recognize: " + message);
                }

                try{
                    JSONObject result_json = new JSONObject();
                    result_json.put("rsp", CMD_RECOGNIZE);
                    result_json.put("status", STATUS_OK);
                    result_json.put("message", message);

                    queue.sendPacketStart(json2bin(result_json));
                }catch(Exception ex){
                    Log.e(MainActivity.TAG, ex.getMessage());
                }
            }

            public void	onError(int error) {
                Log.e(MainActivity.TAG, "Recognize Error: " + error);
                recognizeActive = false;

                if( recDevice != null )
                    mBluetoothHeadset.stopVoiceRecognition(recDevice);

                try{
                    JSONObject result_json = new JSONObject();
                    result_json.put("rsp", CMD_RECOGNIZE);
                    result_json.put("txid", g_txid);
                    result_json.put("status", STATUS_ERROR);

                    queue.sendPacketStart(json2bin(result_json));
                }catch(Exception ex){
                    Log.e(MainActivity.TAG, ex.getMessage());
                }

                // スリープ（これがないと次の音声認識に失敗することがある）
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {}
            }

            public void	onBeginningOfSpeech() {}
            public void	onEndOfSpeech() {}
            public void	onBufferReceived(byte[] buffer) {}
            public void	onPartialResults(Bundle results) {}
            public void	onRmsChanged(float rmsdB) {}
            public void	onEvent(int eventType, Bundle params) {}
        });
    }

    private void startLocation() {
        Log.d(MainActivity.TAG, "startLocation" );

        if (locationManager == null)
            return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return;

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MinTime, MinDistance, locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                Log.d(MainActivity.TAG, "onLocationChanged");
                LocationService.this.location = location;
            }

            @Override
            public void onStatusChanged(String s, int i, Bundle bundle) {}
            @Override
            public void onProviderEnabled(String s) {}
            @Override
            public void onProviderDisabled(String s) {}
        });
    }

    private BluetoothProfile.ServiceListener mProfileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            Log.d(MainActivity.TAG, "onServiceConnected");
            if (profile != BluetoothProfile.HEADSET)
                return;

            mBluetoothHeadset = (BluetoothHeadset)proxy;
            List<BluetoothDevice> devices = mBluetoothHeadset.getConnectedDevices();
            Log.d(MainActivity.TAG, "size=" + devices.size());
            if(devices.size() > 0){
                recDevice = devices.get(0);
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            Log.d(MainActivity.TAG, "onServiceDisconnected");

            if (profile != BluetoothProfile.HEADSET)
                return;

            recDevice = null;
        }
    };

    @Override
    public void onDestroy() {
        Log.d(MainActivity.TAG, "onDestroy");

        if( mScanner != null ){
            mScanner.stopScan(mScanCallback);
            mScanner = null;
        }
        if( mConnGatt != null ){
            try {
                mConnGatt.disconnect();
                mConnGatt.close();
            }catch (Exception ex){}
            mConnGatt = null;
        }
        if(tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            }catch (Exception ex){}
            tts = null;
        }
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
    }

    public class LocationBinder extends Binder {
        public void connectDevice(BluetoothDevice device){
            if( mScanner != null ) {
                mDevice = device;
                mConnGatt = device.connectGatt(context, true, mGattcallback);
            }
        }

        public void setTargetLocation(Location location){
            targetLocation = location;
        }

        public Location getTargetLocation(){
            return targetLocation;
        }

        public ConnectionState isConnected(){
            return isConnected;
        }

        public Location getLastLocation(){
            return location;
        }

        public void setEnable(boolean enable){
            isEnable = enable;
        }

        public boolean getEnable(){
            return isEnable;
        }

        public BluetoothDevice[] getDeviceList(){
            return deviceList.toArray(new BluetoothDevice[0]);
        }

        public BluetoothDevice getDevice(){
            return mDevice;
        }
    }

    private final IBinder mBinder = new LocationBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
