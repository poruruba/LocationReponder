package com.example.locationresponder;

import android.os.Handler;
import android.os.Message;

public class UIHandler extends Handler{
    public static final int MSG_ID_TEXT = 0x0000;
    public static final int MSG_ID_INT =  0x0001;
    public static final int MSG_ID_BARRAY =  0x0002;
    public static final int MSG_ID_NONE =  0x0003;
    public static final int MSG_ID_PRC_BASE = 0x0100;
    public static final int MSG_ID_OBJ_BASE = 0x0100;

    public UIHandler(Handler.Callback callback)
    {
        super( callback );
    }

    public void sendUIMessage( int what, Object obj )
    {
        Message msg = obtainMessage(what, obj);
        sendMessage(msg);
    }

    public void sendUIMessage( int what, int arg1, Object obj )
    {
        Message msg = obtainMessage(what, obj);
        msg.arg1 = arg1;
        sendMessage(msg);
    }

    public void sendUIMessage( int what, int arg1, int arg2, Object obj )
    {
        Message msg = obtainMessage(what, obj);
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        sendMessage(msg);
    }

    public void sendTextMessage( String obj )
    {
        sendUIMessage(MSG_ID_TEXT, obj);
    }

    public void sendIntMessage( int val )
    {
        sendUIMessage(MSG_ID_INT, val );
    }
}