package com.example.locationresponder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.provider.Settings;
import android.location.LocationManager;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, ListView.OnItemClickListener, CheckBox.OnCheckedChangeListener, UIHandler.Callback {
    UIHandler handler;
    public static final String TAG = "LocResp";
    private static final int REQUEST_PERMISSION_LOCATION = 101;
    private static final int REQUEST_PERMISSION_AUDIO = 102;
    private static final int RESULT_CODE_MAP = 201;
    boolean isLocationReady = false;
    boolean isAudioReady = false;
    LocationService.LocationBinder binder;
    Location location;
    Location targetLocation;
    private static final int UIMSG_TOAST = 2001;
    private static final int UIMSG_UPDATE = 2003;
    ClipboardManager clipboardManager;
    boolean isConnecting = false;
    private static final double DEFAULT_LATITUDE = 35.465943;
    private static final double DEFAULT_LONGITUDE = 139.622313;
    private static final int INVOKE_INTERVAL = 1000;
    ArrayList<BleDeviceInfo> deviceList;
    ArrayAdapter<BleDeviceInfo> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new UIHandler(this);

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        final boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        if (!gpsEnabled) {
            Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            startActivity(settingsIntent);
        }

        clipboardManager = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);

        checkLocationPermissions();
        checkAudioPermissions();

        Button btn;
        btn = (Button)findViewById(R.id.btn_start);
        btn.setOnClickListener(this);
        btn = (Button)findViewById(R.id.btn_status_googlemap);
        btn.setOnClickListener(this);
        btn = (Button)findViewById(R.id.btn_googlemap);
        btn.setOnClickListener(this);
        ImageButton imgbtn;
        imgbtn = (ImageButton)findViewById(R.id.btn_status_clipboard);
        imgbtn.setOnClickListener(this);

        CheckBox check;
        check = (CheckBox)findViewById(R.id.chk_enabled);
        check.setOnCheckedChangeListener(this);

        ListView list;
        list = (ListView)findViewById(R.id.list_device);
        list.setOnItemClickListener(this);

        deviceList = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceList);
        list.setAdapter(adapter);

        Intent serviceIntent = new Intent(this, LocationService.class);
        bindService(serviceIntent, mConnection, 0);

        TimerTask task = new TimerTask() {
            @Override
            public void run(){
                handler.sendUIMessage(UIHandler.MSG_ID_TEXT, UIMSG_UPDATE, null);
            }
        };
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(task, 0, INVOKE_INTERVAL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if( resultCode == RESULT_OK ) {
            switch (requestCode) {
                case RESULT_CODE_MAP:
                    if( data.hasExtra("latitude") && data.hasExtra("longitude")){
                        double latitude = data.getDoubleExtra("latitude", 0.0f);
                        double lontigude = data.getDoubleExtra("longitude", 0.0f);
                        targetLocation = new Location("");
                        targetLocation.setLatitude(latitude);
                        targetLocation.setLongitude(lontigude);
                        if( binder != null )
                            binder.setTargetLocation(targetLocation);
                    }
                    break;
            }
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch(buttonView.getId() ){
            case R.id.chk_enabled:{
                if( binder == null )
                    return;

                binder.setEnable(isChecked);
                break;
            }
        }
    }

    @Override
    public boolean handleMessage(Message message) {
        switch (message.what) {
            case UIHandler.MSG_ID_TEXT:{
                if( message.arg1 == UIMSG_TOAST ){
                    Toast.makeText(this, (String)message.obj, Toast.LENGTH_SHORT).show();
                }else
                if( message.arg1 == UIMSG_UPDATE ){
                    updateStatus();
                }
            }
        }
        return false;
    }

    private  void checkAudioPermissions(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_AUDIO);
        }else {
            isAudioReady = true;
        }
    }

    private  void checkLocationPermissions(){
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_LOCATION);
        }else {
            isLocationReady = true;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_PERMISSION_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isLocationReady = true;
            }else{
                Toast.makeText(this, "位置情報の許可がないので計測できません", Toast.LENGTH_LONG).show();
            }
        }
        if (requestCode == REQUEST_PERMISSION_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                isAudioReady = true;
            }else{
                Toast.makeText(this, "音声認識の許可がないので音声認識できません", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch(view.getId()){
            case R.id.btn_start:{
                if( binder == null ) {
                    if (!isLocationReady)
                        return;

                    Intent intent = new Intent(this, LocationService.class);
                    startForegroundService(intent);
                }else{
                    Intent intent = new Intent(this, LocationService.class);
                    stopService(intent);
                }
                break;
            }
            case R.id.btn_googlemap:{
                Intent intent = new Intent(this, MapsActivity.class);
                Location location = targetLocation;
                if( location == null )
                    location = this.location;
                if( location != null ) {
                    intent.putExtra("latitude", location.getLatitude());
                    intent.putExtra("longitude", location.getLongitude());
                }else{
                    intent.putExtra("latitude", DEFAULT_LATITUDE);
                    intent.putExtra("longitude", DEFAULT_LONGITUDE);
                }
                startActivityForResult(intent, RESULT_CODE_MAP);
                break;
            }
            case R.id.btn_status_googlemap: {
                if( location == null )
                    return;
                Uri uri = Uri.parse("geo:" + location.getLatitude() + "," + location.getLongitude() + "?z=16");
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                }
                break;
            }
            case R.id.btn_status_clipboard: {
                EditText edit;
                edit = (EditText)findViewById(R.id.edit_message);
                String message = edit.getText().toString();
                setCopy(message);
                handler.sendUIMessage(UIHandler.MSG_ID_TEXT, UIMSG_TOAST, "クリップボードにコピーしました");
                break;
            }
        }
    }

    private void setCopy(String text){
        if( clipboardManager == null )
            return;

        clipboardManager.setPrimaryClip(ClipData.newPlainText("", text));
    }


    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(MainActivity.TAG, "onServiceConnected");

            Button btn;
            btn = (Button)findViewById(R.id.btn_start);
            btn.setText("Stop");
            binder = (LocationService.LocationBinder)service;
            updateStatus();

            LinearLayout layout;
            layout = (LinearLayout)findViewById(R.id.layout_device_list );
            layout.setVisibility(View.VISIBLE);
            layout = (LinearLayout)findViewById(R.id.layout_device_info );
            layout.setVisibility(View.GONE);
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(MainActivity.TAG, "onServiceDisconnected");
            binder = null;
            isConnecting = false;

            adapter.clear();

            Button btn;
            btn = (Button)findViewById(R.id.btn_start);
            btn.setText("Start");

            LinearLayout layout;
            layout = (LinearLayout)findViewById(R.id.layout_device_list );
            layout.setVisibility(View.GONE);
            layout = (LinearLayout)findViewById(R.id.layout_device_info );
            layout.setVisibility(View.GONE);

            Intent serviceIntent = new Intent(getApplicationContext(), LocationService.class);
            bindService(serviceIntent, mConnection, 0);
        }
    };

    void updateStatus(){
        if( binder == null )
            return;

        BluetoothDevice[] list = binder.getDeviceList();
        for( int i = 0 ; i < list.length ; i++ ){
            boolean found = false;
            for( int j = 0 ; j < deviceList.size() ; j++ ){
                if( list[i].equals(deviceList.get(j).device) ){
                    found = true;
                    break;
                }
            }
            if( !found )
                adapter.add(new BleDeviceInfo(list[i]));
        }

        TextView text;

        location = binder.getLastLocation();
        targetLocation = binder.getTargetLocation();
        if( location != null ){
            text = (TextView) findViewById(R.id.txt_latitude);
            text.setText("緯度：" + String.valueOf(location.getLatitude()) + "°");
            text = (TextView) findViewById(R.id.txt_longitude);
            text.setText("経度：" + String.valueOf(location.getLongitude()) + "°");
            text = (TextView) findViewById(R.id.txt_speed);
            text.setText("速度：" + String.format("%.02f", location.getSpeed() * 3.6f) + "km");
        }
        if( targetLocation != null ){
            text = (TextView) findViewById(R.id.txt_target_latitude);
            text.setText("緯度：" + String.valueOf(targetLocation.getLatitude()) + "°");
            text = (TextView) findViewById(R.id.txt_target_longitude);
            text.setText("経度：" + String.valueOf(targetLocation.getLongitude()) + "°");
            text = (TextView) findViewById(R.id.txt_target_distance);
            if( location != null ) {
                float[] distances = new float[3];
                Location.distanceBetween(location.getLatitude(), location.getLongitude(), targetLocation.getLatitude(), targetLocation.getLongitude(), distances);
                text.setText("距離：" + distances[0] + "m");
            }else{
                text.setText("距離：");
            }
        }

        LocationService.ConnectionState connected = binder.isConnected();
        text = (TextView) findViewById(R.id.txt_connected);
        if( connected == LocationService.ConnectionState.Connected )
            text.setText( "接続済");
        else if( connected == LocationService.ConnectionState.Connecting )
            text.setText( "接続試行中");
        else
            text.setText( "未選択");

        boolean enabled = binder.getEnable();
        CheckBox check;
        check = (CheckBox)findViewById(R.id.chk_enabled);
        check.setChecked(enabled);

        BluetoothDevice device = binder.getDevice();
        if( device != null ){
            text = (TextView)findViewById(R.id.txt_device_name);
            text.setText(device.getName());
            text = (TextView)findViewById(R.id.txt_device_address);
            text.setText(device.getAddress());
        }
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        if( binder != null ) {
            BluetoothDevice device = deviceList.get(position).device;
            binder.connectDevice(device);

            isConnecting = true;
            LinearLayout layout;
            layout = (LinearLayout)findViewById(R.id.layout_device_list );
            layout.setVisibility(View.GONE);
            layout = (LinearLayout)findViewById(R.id.layout_device_info );
            layout.setVisibility(View.VISIBLE);
        }
    }

    class BleDeviceInfo{
        BluetoothDevice device;

        BleDeviceInfo(BluetoothDevice device){
            this.device = device;
        }

        public String toString(){
            return device.getName();
        }
    }
}
