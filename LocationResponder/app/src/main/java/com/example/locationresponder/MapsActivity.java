package com.example.locationresponder;

import androidx.fragment.app.FragmentActivity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, View.OnClickListener {
    private GoogleMap mMap;
    double latitude = -1;
    double longitude = -1;
    Marker marker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        Intent intent = getIntent();
        latitude = intent.getDoubleExtra("latitude", -1.0f);
        longitude = intent.getDoubleExtra("longitude", -1.0f);
    }


    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        LatLng latlng = new LatLng(latitude, longitude);
        marker = mMap.addMarker(new MarkerOptions().position(latlng));
        mMap.moveCamera(CameraUpdateFactory.newLatLng(latlng));

        mMap.moveCamera(CameraUpdateFactory.zoomTo(16.0f));

        mMap.setOnCameraMoveListener(new GoogleMap.OnCameraMoveListener(){
            @Override
            public void onCameraMove(){
                CameraPosition position = mMap.getCameraPosition();
                marker.setPosition(position.target);
            }
        });

        updateLocationUI();

        Button btn;
        btn = (Button)findViewById(R.id.btn_map_select);
        btn.setOnClickListener(this);
        btn = (Button)findViewById(R.id.btn_map_cancel);
        btn.setOnClickListener(this);
    }

    private void updateLocationUI() {
        try {
            mMap.setMyLocationEnabled(true);
            mMap.getUiSettings().setMyLocationButtonEnabled(true);
        } catch (SecurityException e)  {
            Log.e(MainActivity.TAG, e.getMessage());
        }
    }

    @Override
    public void onClick(View view) {
        switch(view.getId()){
            case R.id.btn_map_select:
                // 返すデータ(Intent&Bundle)の作成
                Intent intent = new Intent();
                LatLng latlng = mMap.getCameraPosition().target;
                intent.putExtra("latitude", latlng.latitude);
                intent.putExtra("longitude", latlng.longitude);
                setResult(RESULT_OK, intent);

                finish();
                break;
            case R.id.btn_map_cancel:
                setResult(RESULT_CANCELED);
                finish();
                break;
        }
    }
}
