package com.example.locationresponder;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.util.Log;
import org.json.JSONObject;

public class PacketQueue {
    public static final String TAG = "PacketQueue";

    public byte[] g_recv_buffer = new byte[1024];
    public int g_received_len = 0;
    byte g_expected_slot;
    int g_expected_len = 0;
    byte[] g_send_buffer = null;
    int g_send_offset;
    byte g_send_slot;
    public int UUID_VALUE_SIZE;

    public boolean isProgress = false;

    BluetoothGatt mConnGatt;
    BluetoothGattCharacteristic charSend;

    public PacketQueue( int uuid_value_size, BluetoothGatt gatt, BluetoothGattCharacteristic charSend ){
        this.UUID_VALUE_SIZE = uuid_value_size;
        this.mConnGatt = gatt;
        this.charSend = charSend;
    }

    public int parsePacket(byte[] value){
        if( g_expected_len > 0 && value[0] != g_expected_slot)
            g_expected_len = 0;
        if( g_expected_len == 0 ) {
            if (value[0] != (byte)0x83) {
                Log.e(MainActivity.TAG, "invalid packet");
                return -1;
            }
            g_received_len = 0;
            g_expected_len = (((value[1] << 8) & 0x00ff) | (value[2] & 0x00ff));
            System.arraycopy(value, 3, g_recv_buffer, g_received_len, value.length - 3);
            g_received_len += value.length - 3;
            g_expected_slot = 0x00;
        }else{
            System.arraycopy(value, 1, g_recv_buffer, g_received_len, value.length - 1);
            g_received_len += value.length - 1;
            g_expected_slot++;
        }

        if( g_received_len >= g_expected_len) {
            g_expected_len = 0;
            return g_received_len;
        }else{
            return 0;
        }
    }

    public void sendPacketNext(){
        if( !isProgress )
            return;

        int packet_size = g_send_buffer.length - g_send_offset;
        if (packet_size >= (UUID_VALUE_SIZE - 1))
            packet_size = UUID_VALUE_SIZE - 1;
        byte[] value_write = new byte[1 + packet_size];
        value_write[0] = g_send_slot++;
        System.arraycopy(g_send_buffer, g_send_offset, value_write, 1, packet_size);

        if (!charSend.setValue(value_write)) {
            Log.d(MainActivity.TAG, "setValue error");
            isProgress = false;
            return;
        }

        boolean ret = mConnGatt.writeCharacteristic(charSend);
        if (!ret) {
            Log.d(MainActivity.TAG, "writeCharacteristic error");
            isProgress = false;
            return;
        }

        g_send_offset += packet_size;

        if( g_send_offset >= g_send_buffer.length )
            isProgress = false;
    }

    public void sendPacketStart(byte[] buffer){
        if( isProgress )
            return;

        try {
            byte[] send_buffer = buffer;

            int packet_size = send_buffer.length;
            if (packet_size > (UUID_VALUE_SIZE - 3))
                packet_size = UUID_VALUE_SIZE - 3;
            byte[] value_write = new byte[3 + packet_size];
            value_write[0] = (byte) 0x83;
            value_write[1] = (byte) ((send_buffer.length >> 8) & 0xff);
            value_write[2] = (byte) (send_buffer.length & 0xff);
            System.arraycopy(send_buffer, 0, value_write, 3, packet_size);

            if (!charSend.setValue(value_write)) {
                Log.d(TAG, "setValue error");
                return;
            }

            if( packet_size < send_buffer.length ) {
                isProgress = true;
                g_send_buffer = send_buffer;
                g_send_slot = 0;
                g_send_offset = packet_size;
            }

            boolean ret = mConnGatt.writeCharacteristic(charSend);
            if (!ret) {
                Log.d(TAG, "writeCharacteristic error");
                isProgress = false;
                return;
            }
        }catch(Exception ex){
            Log.e(TAG, ex.getMessage());
        }
    }
}
