//#include <Arduino.h>
#include <M5StickC.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include "BLE2902.h"

#include <ArduinoJson.h>

void requestAssistant(String message);
void requestRecognision(void);
void requestBrowser(String url);
void requestSpeech(String message);
void requestVibration(int msec);
void requestLocation(void);
void requestToast(String message);
void requestNotification(String message);

void sendBuffer(uint8_t *p_value, uint16_t len);
void processCommand(void);

#define STATUS_OK           0
#define STATUS_ERROR        -1
#define STATUS_DISABLED     -2
#define CMD_LOCATION      0x01
#define CMD_SPEECH        0x02
#define CMD_HTTP_POST     0x03
#define CMD_VIBRATOR      0x04
#define CMD_NOTIFICATION  0x05
#define CMD_TOAST         0x06
#define CMD_RECOGNIZE     0x07
#define CMD_BROWSER       0x08

#define UUID_SERVICE "08030900-7d3b-4ebf-94e9-18abc4cebede"
#define UUID_WRITE "08030901-7d3b-4ebf-94e9-18abc4cebede"
#define UUID_READ "08030902-7d3b-4ebf-94e9-18abc4cebede"
#define UUID_NOTIFY "08030903-7d3b-4ebf-94e9-18abc4cebede"

const char* assistant_url = "【GoogleAssistantサーバのURL】;

BLECharacteristic *pCharacteristic_write;
BLECharacteristic *pCharacteristic_read;
BLECharacteristic *pCharacteristic_notify;

const int message_capacity = JSON_OBJECT_SIZE(8) + 256;
StaticJsonDocument<message_capacity> json_message;
char message_buffer[512];

bool connected = false;
bool isProgress = false;

void debug_dump(const uint8_t *p_bin, uint16_t len){
  for( uint16_t i = 0 ; i < len ; i++ ){
    Serial.print((p_bin[i] >> 4) & 0x0f, HEX);
    Serial.print(p_bin[i] & 0x0f, HEX);
  }
  Serial.println("");
}

class MyCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer){
    connected = true;
    Serial.println("Connected\n");
    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setTextSize(2);
    M5.Lcd.setCursor(0, 0);
    M5.Lcd.print("Connected");
  }

  void onDisconnect(BLEServer* pServer){
    connected = false;

    BLE2902* desc = (BLE2902*)pCharacteristic_notify->getDescriptorByUUID(BLEUUID((uint16_t)0x2902));
    desc->setNotifications(false);

    Serial.println("Disconnected\n");
    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setTextSize(2);
    M5.Lcd.setCursor(0, 0);
    M5.Lcd.print("Disconnected");
  }
};

unsigned short recv_len = 0;
unsigned short expected_len = 0;
unsigned char expected_slot = 0;
unsigned char recv_buffer[1024];

class MyCharacteristicCallbacks : public BLECharacteristicCallbacks{
  void onWrite(BLECharacteristic* pCharacteristic){
    Serial.println("onWrite");
    uint8_t* value = pCharacteristic->getData();
    std::string str = pCharacteristic->getValue(); 

    debug_dump(value, str.length());

    if( expected_len > 0 && value[0] != expected_slot )
        expected_len = 0;

    if( expected_len == 0 ){
      if( value[0] != 0x83 )
          return;
      recv_len = 0;
      expected_len = (value[1] << 8) | value[2];
      memmove(&recv_buffer[recv_len], &value[3], str.length() - 3);
      recv_len += str.length() - 3;
      expected_slot = 0;
      if( recv_len < expected_len )
        return;
    }else{
      memmove(&recv_buffer[recv_len], &value[1], str.length() - 1);
      recv_len += str.length() - 1;
      expected_slot++;
      if( recv_len < expected_len )
        return;
    }
    expected_len = 0;

    processCommand();
  }
/*
  void onStatus(BLECharacteristic* pCharacteristic, Status s, uint32_t code){
  }
  void BLECharacteristicCallbacks::onRead(BLECharacteristic *pCharacteristic){
  };
*/
};

void processCommand(void){
  Serial.println("processCommand");

  DeserializationError err = deserializeJson(json_message, recv_buffer, recv_len);
  if( err ){
    Serial.println("Deserialize error");
    Serial.println(err.c_str());
    return;
  }

  int rsp = json_message["rsp"];
  int status = json_message["status"];
  if( status != STATUS_OK ){
    Serial.println("status != STATUS_OK");
    return;
  }
  switch(rsp){
    case CMD_LOCATION:{
      Serial.println("rsp=CMD_LOCATION");
      double latitude = json_message["latitude"];
      double longitude = json_message["longitude"];
      double speed = json_message["speed"];
      Serial.println(latitude);
      Serial.println(longitude);
      Serial.println(speed);

      if( json_message.containsKey("distance") && json_message.containsKey("direction") ){
        double distance = json_message["distance"];
        double direction = json_message["direction"];
        Serial.println(distance);
        Serial.println(direction);

        int distance_int = (int)distance;
        if( distance_int <= 1 ){
          requestSpeech("目的地周辺です。");
        }else{
          static String DIRECTION_TEXT[] = {"北", "北東", "東", "南東", "南", "南西", "西", "北西" };
          double target = (direction + 360.0 + (360.0 / (8 * 2))) / (360 / 8);
          requestSpeech("目的地まで" + String(distance_int) + "メートル、" + DIRECTION_TEXT[(int)target % 8] + "方向です。速度は、" + String((int)speed) + "キロメートルです。");
        }
      }else{
        requestSpeech("現在位置を確認しました。速度は、" + String((int)speed) + "キロメートルです。");
      }

      break;
    }
    case CMD_RECOGNIZE:{
      Serial.println("rsp=CMD_RECOGNIZE");
      const char* message = json_message["message"];
      Serial.println(message);
      requestAssistant(message);
      break;
    }
    case CMD_HTTP_POST:{
      Serial.println("rsp=CMD_HTTP_POST");
      const char* message = json_message["response"]["text"];
      Serial.println(message);

      requestSpeech(message);
      break;
    }
    case CMD_SPEECH:
    case CMD_VIBRATOR:
    {
      Serial.println("rsp=OK");
      break;
    }
  }
}
 
#define UUID_VALUE_SIZE 20
uint8_t value_write[UUID_VALUE_SIZE];
uint8_t value_read[] = { (uint8_t)((UUID_VALUE_SIZE >> 8) & 0xff), (uint8_t)(UUID_VALUE_SIZE & 0xff) };

void taskServer(void*) {
  BLEDevice::init("M5Stick-C");

  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyCallbacks());

  BLEService *pService = pServer->createService(UUID_SERVICE);

  pCharacteristic_write = pService->createCharacteristic( UUID_WRITE, BLECharacteristic::PROPERTY_WRITE );
  pCharacteristic_write->setAccessPermissions(ESP_GATT_PERM_WRITE);
  pCharacteristic_write->setValue(value_write, sizeof(value_write));
  pCharacteristic_write->setCallbacks(new MyCharacteristicCallbacks());

  pCharacteristic_read = pService->createCharacteristic( UUID_READ, BLECharacteristic::PROPERTY_READ );
  pCharacteristic_read->setAccessPermissions(ESP_GATT_PERM_READ);
  pCharacteristic_read->setValue(value_read, sizeof(value_read));

  pCharacteristic_notify = pService->createCharacteristic( UUID_NOTIFY, BLECharacteristic::PROPERTY_NOTIFY );
  pCharacteristic_notify->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising *pAdvertising = pServer->getAdvertising();
  pAdvertising->addServiceUUID(UUID_SERVICE);
  pAdvertising->start();

  vTaskDelay(portMAX_DELAY); //delay(portMAX_DELAY);
}

void sendBuffer(uint8_t *p_value, uint16_t len){
  Serial.println("SendBuffer");
  
  BLE2902* desc = (BLE2902*)pCharacteristic_notify->getDescriptorByUUID(BLEUUID((uint16_t)0x2902));
  if( !desc->getNotifications() )
    return;

  int offset = 0;
  int slot = 0;
  int packet_size = 0;
  do{
    if( offset == 0){
      value_write[0] = 0x83;
      value_write[1] = (len >> 8) & 0xff;
      value_write[2] = len & 0xff;
      packet_size = len - offset;
      if( packet_size > (UUID_VALUE_SIZE - 3) )
        packet_size = UUID_VALUE_SIZE - 3;
      memmove(&value_write[3], &p_value[offset], packet_size);

      offset += packet_size;
      packet_size += 3;

    }else{
      value_write[0] = slot++;
      packet_size = len - offset;
      if( packet_size > (UUID_VALUE_SIZE - 1) )
        packet_size = UUID_VALUE_SIZE - 1;
      memmove(&value_write[1], &p_value[offset], packet_size);

      offset += packet_size;
      packet_size += 1;
    }
    
    pCharacteristic_notify->setValue(value_write, packet_size);
    pCharacteristic_notify->notify();

  }while(packet_size >= UUID_VALUE_SIZE);  
}

void requestLocation(void){
  Serial.println("requestLocation");

  json_message.clear();
  json_message["cmd"] = CMD_LOCATION;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestToast(String message){
  Serial.print("requestToast: ");
  Serial.println(message);

  json_message.clear();
  json_message["cmd"] = CMD_TOAST;
  json_message["message"] = message;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestNotification(String message){
  Serial.print("requestNotification: ");
  Serial.println(message);

  json_message.clear();
  json_message["cmd"] = CMD_NOTIFICATION;
  json_message["message"] = message;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestSpeech(String message){
  Serial.print("requestSpeech: ");
  Serial.println(message);

  json_message.clear();
  json_message["cmd"] = CMD_SPEECH;
  json_message["message"] = message;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestVibration(int msec){
  json_message.clear();
  json_message["cmd"] = CMD_VIBRATOR;
  json_message["duration"] = msec;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestRecognision(void){
  json_message.clear();
  json_message["cmd"] = CMD_RECOGNIZE;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestBrowser(String url){
  json_message.clear();
  json_message["cmd"] = CMD_BROWSER;
  json_message["url"] = url;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void requestAssistant(String message){
  json_message.clear();
  json_message["cmd"] = CMD_HTTP_POST;
  json_message["url"] = assistant_url;
  json_message["request"]["message"] = message;

  size_t writen = serializeJson(json_message, message_buffer, sizeof(message_buffer));
  sendBuffer((uint8_t*)message_buffer, writen);
}

void setup() {
  M5.begin();

  M5.IMU.Init();
  M5.Axp.ScreenBreath(9);

  M5.Lcd.setRotation(3);
  M5.Lcd.fillScreen(BLACK);
  M5.Lcd.setTextSize(2);
  M5.Lcd.println("General Button");
  delay(1000);

//  M5.Lcd.println("start Serial");
  Serial.begin(9600);
  Serial.println("setup");

  xTaskCreate(taskServer, "server", 20000, NULL, 5, NULL);
}

void loop() {
  M5.update();

  if ( M5.BtnA.wasPressed() )
      requestLocation();

  if ( M5.BtnB.wasPressed() )
      requestRecognision();
}