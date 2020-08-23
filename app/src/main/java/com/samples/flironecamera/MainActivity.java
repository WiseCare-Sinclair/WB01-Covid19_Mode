/*
 * ******************************************************************
 * @title FLIR THERMAL SDK
 * @file MainActivity.java
 * @Author FLIR Systems AB
 *
 * @brief  Main UI of test application
 *
 * Copyright 2019:    FLIR Systems
 * ******************************************************************/
package com.samples.flironecamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.BuildConfig;  //added
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.image.Point;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.log.ThermalLog;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.samples.flironecamera.tflite.Classifier;
import com.samples.flironecamera.tflite.TFLiteObjectDetectionAPIModel;


import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import org.jetbrains.annotations.NotNull;

/**
 * Sample application for scanning a FLIR ONE or a built in emulator
 * <p>
 * See the {@link CameraHandler} for how to preform discovery of a FLIR ONE camera, connecting to it and start streaming images
 * <p>
 * The MainActivity is primarily focused to "glue" different helper classes together and updating the UI components
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    //Handles Android permission for eg Network
    private PermissionHandler permissionHandler;

    //Handles network camera operations
    private CameraHandler cameraHandler;

    private Identity connectedIdentity = null;
    private TextView connectionStatus;
    private TextView discoveryStatus;
    private TextView temperature;
    private TextView facechecker;
    private TextView label;
    private TextView debugger;

    private Handler handler;
    private HandlerThread handlerThread;

    private ImageView photoImage;


    private static final int MAX_BUFFER_SIZE = 21;
    private LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue<>(MAX_BUFFER_SIZE);
    private LinkedBlockingQueue<Queue<FaceDataHolder>> facesBuffer = new LinkedBlockingQueue<>(MAX_BUFFER_SIZE);

    Queue<FaceDataHolder> facedataQueue = new LinkedList<>();

    private UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();


    private FaceDetector faceDetector;


    private static final int TF_OD_API_INPUT_SIZE = 224;
    private static final boolean TF_OD_API_IS_QUANTIZED = false;
    private static final String TF_OD_API_MODEL_FILE = "mask_detector.tflite";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/mask_labelmap.txt";

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    private Classifier detector;
    Bitmap faceBmp = Bitmap.createBitmap(TF_OD_API_INPUT_SIZE, TF_OD_API_INPUT_SIZE, Bitmap.Config.ARGB_8888);

    private enum DetectorMode {
        TF_OD_API
    }

    ThermalImage thermalImg;
    Bitmap dcBmp;
    Bitmap msxBmp;
    int tW, tH, bW, bH;
    double temp = 0;
    float widthscale, heightscale;
    List<Face> Faces;

    /**
     * Show message on the screen
     */
    public interface ShowMessage {
        void show(String message);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        FaceDetectorOptions options =
                new FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setContourMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .build();


        faceDetector = FaceDetection.getClient(options);

        try {
            detector =
                    TFLiteObjectDetectionAPIModel.create(
                            getAssets(),
                            TF_OD_API_MODEL_FILE,
                            TF_OD_API_LABELS_FILE,
                            TF_OD_API_INPUT_SIZE,
                            TF_OD_API_IS_QUANTIZED);
            //cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }


        ThermalLog.LogLevel enableLoggingInDebug = BuildConfig.DEBUG ? ThermalLog.LogLevel.DEBUG : ThermalLog.LogLevel.NONE;

        //ThermalSdkAndroid has to be initiated from a Activity with the Application Context to prevent leaking Context,
        // and before ANY using any ThermalSdkAndroid functions
        //ThermalLog will show log from the Thermal SDK in standards android log framework
        ThermalSdkAndroid.init(getApplicationContext(), enableLoggingInDebug);

        permissionHandler = new PermissionHandler(showMessage, MainActivity.this);

        cameraHandler = new CameraHandler();

        setupViews();

        showSDKversion(ThermalSdkAndroid.getVersion());
    }

    @Override
    public synchronized void onStart(){
        Log.d(TAG, "onStart");
        super.onStart();
    }

    @Override
    public synchronized void onResume(){
        Log.d(TAG, "onResume " + this);
        super.onResume();

        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    @Override
    public synchronized void onPause() {
        Log.d(TAG, "onPause " + this);

        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            Log.e(TAG, "Exception!");
        }

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        Log.d(TAG,"onStop " + this);
        super.onStop();
    }

    @Override
    public synchronized void onDestroy() {
        Log.d(TAG,"onDestroy " + this);
        super.onDestroy();
    }

    public void startDiscovery(View view) {
        startDiscovery();
    }

    public void stopDiscovery(View view) {
        stopDiscovery();
    }


    public void connectFlirOne(View view) {
        connect(cameraHandler.getFlirOne());
    }

    public void connectSimulatorOne(View view) {
        connect(cameraHandler.getCppEmulator());
    }

    public void connectSimulatorTwo(View view) {
        connect(cameraHandler.getFlirOneEmulator());
    }

    public void disconnect(View view) {
        disconnect();
    }

    /**
     * Handle Android permission request response for Bluetooth permissions
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions, @NotNull int[] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult() called with: requestCode = [" + requestCode + "], permissions = [" + Arrays.toString(permissions) + "], grantResults = [" + Arrays.toString(grantResults) + "]");
        permissionHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Connect to a Camera
     */
    private void connect(Identity identity) {
        //We don't have to stop a discovery but it's nice to do if we have found the camera that we are looking for
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            Log.d(TAG, "connect(), in *this* code sample we only support one camera connection at the time");
            showMessage.show("connect(), in *this* code sample we only support one camera connection at the time");
            return;
        }

        if (identity == null) {
            Log.d(TAG, "connect(), can't connect, no camera available");
            showMessage.show("connect(), can't connect, no camera available");
            return;
        }

        connectedIdentity = identity;

        updateConnectionText(identity, "CONNECTING");
        //IF your using "USB_DEVICE_ATTACHED" and "usb-device vendor-id" in the Android Manifest
        // you don't need to request permission, see documentation for more information
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }

    }

    private UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(@NotNull Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(@NotNull Identity identity) {
            MainActivity.this.showMessage.show("Permission was denied for identity ");
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, final Identity identity) {
            MainActivity.this.showMessage.show("Error when asking for permission for FLIR ONE, error:"+errorType+ " identity:" +identity);
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");
                    cameraHandler.startStream(streamDataListener);
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Could not connect: " + e);
                    updateConnectionText(identity, "DISCONNECTED");
                });
            }
        }).start();
    }

    /**
     * Disconnect to a camera
     */
    private void disconnect() {
        updateConnectionText(connectedIdentity, "DISCONNECTING");
        connectedIdentity = null;
        Log.d(TAG, "disconnect() called with: connectedIdentity = [" + connectedIdentity + "]");
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> {
                    updateConnectionText(null, "DISCONNECTED");
            });
        }).start();
    }

    /**
     * Update the UI text for connection status
     */
    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        connectionStatus.setText(getString(R.string.connection_status_text, deviceId + " " + status));
    }

    /**
     * Start camera discovery
     */
    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    /**
     * Stop camera discovery
     */
    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    /**
     * Callback for discovery status, using it to update UI
     */
    private CameraHandler.DiscoveryStatus discoveryStatusListener = new CameraHandler.DiscoveryStatus() {
        @Override
        public void started() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
        }

        @Override
        public void stopped() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
        }
    };

    /**
     * Camera connecting state thermalImageStreamListener, keeps track of if the camera is connected or not
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private ConnectionStatusListener connectionStatusListener = new ConnectionStatusListener() {
        @Override
        public void onDisconnected(@org.jetbrains.annotations.Nullable ErrorCode errorCode) {
            Log.d(TAG, "onDisconnected errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateConnectionText(connectedIdentity, "DISCONNECTED");
                }
            });
        }
    };

    // can be used when running inference in background
    protected synchronized void runInBackground(final Runnable r){
        if (handler != null){
            handler.post(r);
        }
    }

    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {

        // not used
        @Override
        public void images(FrameDataHolder frameDataHolder) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    //msxImage.setImageBitmap(dataHolder.msxBitmap);
                    photoImage.setImageBitmap(frameDataHolder.dcBitmap);
                }
            });
        }

        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap, ThermalImage thermalImage) {

            // set variables
            msxBmp = msxBitmap;
            dcBmp = dcBitmap;
            thermalImg = thermalImage;

            tW = thermalImg.getWidth();
            tH = thermalImg.getHeight();

            bW = dcBmp.getWidth();
            bH = dcBmp.getHeight();

            widthscale = ((float) tW) / bW;
            heightscale = ((float) tH) / bH;
            Log.d("DEBUG", tW + " " + tH + " " + bW + " " + bH);

            // detecting faces
            InputImage image = InputImage.fromBitmap(dcBmp, 0);
            faceDetector
                    .process(image)
                    .addOnSuccessListener(new OnSuccessListener<List<Face>>(){
                        // success
                        @Override
                        public void onSuccess(List<Face> faces){
                            Faces = faces;
                        }
                    })
            .addOnFailureListener(
                    new OnFailureListener() {
                        // fail
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            // debugger.setText("face detection failed");
                            Log.e("DEBUG", "face detection failed " + e);
                        }
                    }
            );

            // put data
            // data: dcBitamp, (masklabels, temps, facequeue with faceBBs, xys)
            // face 1개당 faceBB, masklabel, temp, xy가 들어감
            try {
                try {
                    final Canvas cvFace = new Canvas(faceBmp);

                    // get data from the list of faces
                    if (Faces != null) {
                        for (Face face : Faces) {

                            // variables for each face: faceBB, masklabel, temp, x, y
                            RectF faceBB = new RectF(0, 0, 0, 0);
                            String masklabel;
                            temp = 0;
                            double[] temp_arr;
                            float x = 0;
                            float y = 0;

                            // run inference and get mask label
                            final RectF boundingBox = new RectF(face.getBoundingBox());
                            final boolean goodConfidence = true;
                            if (boundingBox != null && goodConfidence) {
                                faceBB = new RectF(boundingBox);

                                // resize
                                float sx = ((float) TF_OD_API_INPUT_SIZE) / faceBB.width();
                                float sy = ((float) TF_OD_API_INPUT_SIZE) / faceBB.height();
                                Matrix mat = new Matrix();
                                mat.postTranslate(-faceBB.left, -faceBB.top);
                                mat.postScale(sx, sy);

                                cvFace.drawBitmap(dcBmp, mat, null);

                                //run inference
                                final List<Classifier.Recognition> resultsAux = detector.recognizeImage(faceBmp);

                                // get mask label
                                if (resultsAux.size() > 0) {

                                    Classifier.Recognition result = resultsAux.get(0);

                                    float conf = result.getConfidence();
                                    if (conf >= 0.6f) {
                                        masklabel = result.getTitle();
                                    } else {
                                        masklabel = "BadConfidence";
                                    }
                                } else {
                                    masklabel = "DetectionFailed";
                                }
                            } else {
                                masklabel = "getBBFailed";
                            }

                            // get temperature value
                            try {
                                // xy point tracing faces
                                x = faceBB.centerX();
                                y = (float) (Math.min(faceBB.top, faceBB.bottom) + Math.abs(faceBB.bottom - faceBB.top) * 0.33);

                                // location for generating a rectangle
                                int thermal_rect_x = (int) (Math.min(faceBB.left, faceBB.right) * widthscale);
                                int thermal_rect_y = (int) (Math.min(faceBB.top, faceBB.bottom) * heightscale);
                                int thermal_rect_w = (int) (faceBB.width() * widthscale);
                                int thermal_rect_h = (int) (faceBB.height() * heightscale);
                                Rectangle rect = new Rectangle(thermal_rect_x, thermal_rect_y, thermal_rect_w, thermal_rect_h);

                                // get temp values
                                temp_arr = thermalImg.getValues(rect);

                                // get max temp value
                                if (temp_arr.length > 0) {
                                    Arrays.sort(temp_arr);
                                    temp = temp_arr[temp_arr.length - 1] - 273.15;
                                    temp = (double) Math.round(temp * 100) / 100.0;
                                } else {
                                    temp = -1; // cannot get temp value
                                }


                            } catch (Exception e) {
                                Log.e("DEBUG", "exception while getting temp values " + e);
                            }

                            // put face data into face data queue
                            FaceDataHolder FDHolder = new FaceDataHolder(faceBB, masklabel, temp, x, y);
                            facedataQueue.offer(FDHolder);
                        }
                        // processing faces is finished
                    }
                }
                catch(Exception e){
                    Log.e("DEBUG", "exception while getting faces " + e);
                }

                // put frame data and facequeue corresponding to that frame into buffers
                framesBuffer.put(new FrameDataHolder(msxBmp, dcBmp, thermalImg));
                facesBuffer.put(facedataQueue);
            } catch (InterruptedException e) {
                //if interrupted while waiting for adding a new item in the queue
                Log.e(TAG,"images(), unable to add incoming images to frames buffer, exception:"+e);
                Log.e(TAG,"images(), unable to add incoming data to faces buffer, exception:"+e);
            }

            // poll and show data
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "framebuffer size:" + framesBuffer.size());

                    // poll data
                    FrameDataHolder frame_poll = framesBuffer.poll();
                    Queue<FaceDataHolder> facedataQueue = facesBuffer.poll();

                    if (frame_poll == null || facedataQueue == null) {
                        Log.d(TAG, "null is found");
                        debugger.setText("null is found");
                    } else {
                        int facesize = facedataQueue.size();

                        Log.d("DEBUG", facesize + " faces found");

                        // prepare stringbuilder for textView
                        StringBuilder tmp = new StringBuilder("Max Temp Value = ");
                        StringBuilder lbl = new StringBuilder();

                        // prepare draw tools
                        Bitmap dcBmp = frame_poll.dcBitmap;
                        final Canvas cv = new Canvas(dcBmp);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setStrokeWidth(2.0f);

                        final Paint paint2 = new Paint();
                        paint2.setColor(Color.RED);
                        paint2.setStrokeWidth(30f);

                        for (int i = 0; i < facesize; ++i){
                            FaceDataHolder FDHolder = facedataQueue.poll();
                            if (FDHolder != null) {

                                //temp logging
                                Log.d("DEBUG", "Max Temp Value:" + FDHolder.temp);

                                //label logging
                                Log.d("DEBUG", "Label is " + FDHolder.masklabel);

                                //making strings
                                tmp.append(FDHolder.temp);
                                tmp.append(" ");
                                lbl.append(FDHolder.masklabel);
                                lbl.append(" ");

                                //draw rect
                                RectF faceBB = FDHolder.faceBB;
                                float X = FDHolder.x;
                                float Y = FDHolder.y;
                                cv.drawRect(faceBB, paint);
                                cv.drawPoint(X, Y, paint2);
                            }
                            else{
                                debugger.setText("facedata is null");
                            }
                        }

                        // Set data for showing
                        photoImage.setImageBitmap(dcBmp);
                        temperature.setText(tmp.toString());
                        facechecker.setText(facesize + " faces found");
                        label.setText(lbl.toString());
                    }
                }
            });
        }
    };

    /**
     * Camera Discovery thermalImageStreamListener, is notified if a new camera was found during a active discovery phase
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(Identity identity) {
            Log.d(TAG, "onCameraFound identity:" + identity);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cameraHandler.add(identity);
                }
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface communicationInterface, ErrorCode errorCode) {
            Log.d(TAG, "onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopDiscovery();
                    MainActivity.this.showMessage.show("onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);
                }
            });
        }
    };

    private ShowMessage showMessage = new ShowMessage() {
        @Override
        public void show(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    private void showSDKversion(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
        String sdkVersionText = getString(R.string.sdk_version_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }

    private void setupViews() {
        connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);
        temperature = findViewById(R.id.temperature);
        facechecker = findViewById(R.id.facechecker);
        photoImage = findViewById(R.id.photo_image);
        label = findViewById(R.id.label);
        debugger = findViewById(R.id.debugger);
    }

}
