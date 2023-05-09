package com.example.hands_mp_test;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.solutioncore.CameraInput;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutioncore.VideoInput;
import com.google.mediapipe.solutions.hands.HandLandmark;
import com.google.mediapipe.solutions.hands.Hands;
import com.google.mediapipe.solutions.hands.HandsOptions;
import com.google.mediapipe.solutions.hands.HandsResult;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ClasifyHandActivity extends AppCompatActivity {
    private static final String TAG = "ClassifyHandsActivity";
    private Hands hands;
    private static final boolean RUN_ON_GPU = true;
    private enum InputSource {
        UNKNOWN,
        IMAGE,
        VIDEO,
        CAMERA,
    }

    protected  List<String> signNames = Arrays.asList("0/O", "1", "2","3", "4", "5", "A", "B", "C","D", "E", "F/T", "H", "I", "L", "M",
            "N", "P", "R", "S", "U", "W", "Y", "Aw", "Bk", "Cm", "Ik", "Om", "Um");

    private InputSource inputSource = InputSource.UNKNOWN;
    private ActivityResultLauncher<Intent> imageGetter;
    private HandsResultImageView imageView;
    private VideoInput videoInput;
    private ActivityResultLauncher<Intent> videoGetter;
    private CameraInput cameraInput;
    private Boolean CameraOn = false;
    private double[] line;
    private List<double[]> tmpLines = new ArrayList<>();
    private SolutionGlSurfaceView<HandsResult> glSurfaceView;


    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_clasify_hand);
        setupStaticImageDemoUiComponents();
        setupLiveDemoUiComponents();

        Button sprawdzButton = findViewById(R.id.button_sprawdz);
        sprawdzButton.setOnClickListener(
                v -> {
                    try {
                        long startTime = System.currentTimeMillis();
                        double[] lineFromTmp = tmpLines.get(tmpLines.size() - 1);
                        if(lineFromTmp != null) {
                            double[] newline = getNorm_01(lineFromTmp);
                            ModelCLF modelR = new ModelCLF(newline);
                            double[] score = modelR.score(newline);
                            int scoreSign = getIndexOfMaxVal(score);

                            String sign = signNames.get(scoreSign);

                            // sprawdzenie czy faktycznie była łapka
                            if (scoreSign <0) {
                                Toast.makeText(this, "Wykrycie dłoni nie powiodło się, spróbuj ponownie", Toast.LENGTH_LONG).show();
                            }
                            else {
                                Toast.makeText(this, "Wykryty znak: " + sign, Toast.LENGTH_LONG).show();
                            }

                            long difference = System.currentTimeMillis() - startTime;
                            Log.i(TAG, "CZAS DLA JEDNEGO ZNAKU: " + difference);


                            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                            SharedPreferences.Editor ed = sharedPreferences.edit();
                            ed.putInt("Znak", scoreSign);
                            ed.commit();
                            //Log.i(TAG, "WYKRYTY ZNAK: " + scoreSign);
                            onPause();
                            finish();
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "Wykrycie dłoni nie powiodło się, wystąpił błąd - sprawdź czy uruchomiono kamerę i spróbuj ponownie", Toast.LENGTH_LONG).show();
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inputSource == InputSource.CAMERA) {
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
            glSurfaceView.post(this::startCamera);
            glSurfaceView.setVisibility(View.VISIBLE);
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.setVisibility(View.GONE);
            cameraInput.close();
        } else if (inputSource == InputSource.VIDEO) {
            videoInput.pause();
        }
    }

    private Bitmap downscaleBitmap(Bitmap originalBitmap) {
        double aspectRatio = (double) originalBitmap.getWidth() / originalBitmap.getHeight();
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (((double) imageView.getWidth() / imageView.getHeight()) > aspectRatio) {
            width = (int) (height * aspectRatio);
        } else {
            height = (int) (width / aspectRatio);
        }
        return Bitmap.createScaledBitmap(originalBitmap, width, height, false);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private Bitmap rotateBitmap(Bitmap inputBitmap, InputStream imageData) throws IOException {
        int orientation =
                new ExifInterface(imageData)
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return inputBitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.postRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.postRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.postRotate(270);
                break;
            default:
                matrix.postRotate(0);
        }
        return Bitmap.createBitmap(
                inputBitmap, 0, 0, inputBitmap.getWidth(), inputBitmap.getHeight(), matrix, true);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void setupStaticImageDemoUiComponents() {
        imageGetter =
                registerForActivityResult(
                        new ActivityResultContracts.StartActivityForResult(),
                        result -> {
                            Intent resultIntent = result.getData();
                            if (resultIntent != null) {
                                if (result.getResultCode() == RESULT_OK) {
                                    Bitmap bitmap = null;
                                    try {
                                        bitmap =
                                                downscaleBitmap(
                                                        MediaStore.Images.Media.getBitmap(
                                                                this.getContentResolver(), resultIntent.getData()));
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap reading error:" + e);
                                    }
                                    try {
                                        InputStream imageData =
                                                this.getContentResolver().openInputStream(resultIntent.getData());
                                        bitmap = rotateBitmap(bitmap, imageData);
                                    } catch (IOException e) {
                                        Log.e(TAG, "Bitmap rotation error:" + e);
                                    }
                                    if (bitmap != null) {
                                        hands.send(bitmap);
                                    }
                                }
                            }
                        });

        imageView = new HandsResultImageView(this);
    }

    /** Sets up the UI components for the live demo with camera input. */
    private void setupLiveDemoUiComponents() {
        Button startCameraButton = findViewById(R.id.button_start_camera);
        startCameraButton.setOnClickListener(
                v -> {
                    if (inputSource == InputSource.CAMERA) {
                        return;
                    }
                    stopCurrentPipeline();
                    setupStreamingModePipeline(InputSource.CAMERA);
                });
    }

    private void setupStreamingModePipeline(InputSource inputSource) {
        this.inputSource = inputSource;
        hands =
                new Hands(
                        this,
                        HandsOptions.builder()
                                .setStaticImageMode(false)
                                .setMaxNumHands(1)
                                .setRunOnGpu(RUN_ON_GPU)
                                .build());
        hands.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Hands error:" + message));

        if (inputSource == InputSource.CAMERA) {
            cameraInput = new CameraInput(this);
            cameraInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
        } else if (inputSource == InputSource.VIDEO) {
            videoInput = new VideoInput(this);
            videoInput.setNewFrameListener(textureFrame -> hands.send(textureFrame));
        }

        glSurfaceView =
                new SolutionGlSurfaceView<>(this, hands.getGlContext(), hands.getGlMajorVersion());
        glSurfaceView.setSolutionResultRenderer(new HandsResultGlRenderer());
        glSurfaceView.setRenderInputImage(true);
        hands.setResultListener(
                handsResult -> {
                    // Sprawdzenie ile czasu przetwarza 1 ramke:
                    //long startTime = System.currentTimeMillis();
                    line = getAllHandLandmarks(handsResult);
                    tmpLines.add(line);
                    //getClassifivationFromModel(line);
                    //Log.i(TAG, "-----------------------------------" + line);
                    glSurfaceView.setRenderData(handsResult);
                    glSurfaceView.requestRender();

                    //long difference = System.currentTimeMillis() - startTime;
                    //Log.i(TAG, "KLATKA CZAS PRZETWARZANIA: " + difference);
                });

        if (inputSource == InputSource.CAMERA) {
            glSurfaceView.post(this::startCamera);
        }

        FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
        imageView.setVisibility(View.GONE);
        frameLayout.removeAllViewsInLayout();
        frameLayout.addView(glSurfaceView);
        glSurfaceView.setVisibility(View.VISIBLE);
        frameLayout.requestLayout();
    }

    private void startCamera() {
        cameraInput.start(
                this,
                hands.getGlContext(),
                CameraInput.CameraFacing.FRONT,
                glSurfaceView.getWidth(),
                glSurfaceView.getHeight());
    }

    private void stopCurrentPipeline() {
        if (cameraInput != null) {
            cameraInput.setNewFrameListener(null);
            cameraInput.close();
        }
        if (videoInput != null) {
            videoInput.setNewFrameListener(null);
            videoInput.close();
        }
        if (glSurfaceView != null) {
            glSurfaceView.setVisibility(View.GONE);
        }
        if (hands != null) {
            hands.close();
        }
    }

    private double[] getAllHandLandmarks(HandsResult result)
    {
        if (result.multiHandLandmarks().isEmpty()) {
            double[] noValuesFromLandmarks = new double[0];
            Log.i(TAG, "No Handlandmarks");
            return noValuesFromLandmarks;
        }
        else {
            // Znormalizowane Landmarks dla wszystkich punktów:
            //Nadgarstek
            NormalizedLandmark wristLandmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.WRIST);
            // Kciuk
            NormalizedLandmark thumb_CMC_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.THUMB_CMC);
            NormalizedLandmark thumb_MCP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.THUMB_MCP);
            NormalizedLandmark thumb_IP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.THUMB_IP);
            NormalizedLandmark thumb_TIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.THUMB_TIP);
            // Wskazujący
            NormalizedLandmark index_MCP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.INDEX_FINGER_MCP);
            NormalizedLandmark index_PIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.INDEX_FINGER_PIP);
            NormalizedLandmark index_DIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.INDEX_FINGER_DIP);
            NormalizedLandmark index_TIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.INDEX_FINGER_TIP);
            // Srodkowy
            NormalizedLandmark middle_MCP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.MIDDLE_FINGER_MCP);
            NormalizedLandmark middle_PIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.MIDDLE_FINGER_PIP);
            NormalizedLandmark middle_DIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.MIDDLE_FINGER_DIP);
            NormalizedLandmark middle_TIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.MIDDLE_FINGER_TIP);
            // Serdeczny
            NormalizedLandmark ring_MCP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.RING_FINGER_MCP);
            NormalizedLandmark ring_PIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.RING_FINGER_PIP);
            NormalizedLandmark ring_DIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.RING_FINGER_DIP);
            NormalizedLandmark ring_TIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.RING_FINGER_TIP);
            // Mały
            NormalizedLandmark pinky_MCP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.PINKY_MCP);
            NormalizedLandmark pinky_PIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.PINKY_PIP);
            NormalizedLandmark pinky_DIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.PINKY_DIP);
            NormalizedLandmark pinky_TIP_Landmark =
                    result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.PINKY_TIP);

            // Wartości x i y:
            float[] allValuesX = {wristLandmark.getX(),
                    thumb_CMC_Landmark.getX(),
                    thumb_MCP_Landmark.getX(),
                    thumb_IP_Landmark.getX(),
                    thumb_TIP_Landmark.getX(),

                    index_MCP_Landmark.getX(),
                    index_PIP_Landmark.getX(),
                    index_DIP_Landmark.getX(),
                    index_TIP_Landmark.getX(),

                    middle_MCP_Landmark.getX(),
                    middle_PIP_Landmark.getX(),
                    middle_DIP_Landmark.getX(),
                    middle_TIP_Landmark.getX(),

                    ring_MCP_Landmark.getX(),
                    ring_PIP_Landmark.getX(),
                    ring_DIP_Landmark.getX(),
                    ring_TIP_Landmark.getX(),

                    pinky_MCP_Landmark.getX(),
                    pinky_PIP_Landmark.getX(),
                    pinky_DIP_Landmark.getX(),
                    pinky_TIP_Landmark.getX()
            };

            float[] allValuesY = {wristLandmark.getY(),
                    thumb_CMC_Landmark.getY(),
                    thumb_MCP_Landmark.getY(),
                    thumb_IP_Landmark.getY(),
                    thumb_TIP_Landmark.getY(),

                    index_MCP_Landmark.getY(),
                    index_PIP_Landmark.getY(),
                    index_DIP_Landmark.getY(),
                    index_TIP_Landmark.getY(),

                    middle_MCP_Landmark.getY(),
                    middle_PIP_Landmark.getY(),
                    middle_DIP_Landmark.getY(),
                    middle_TIP_Landmark.getY(),

                    ring_MCP_Landmark.getY(),
                    ring_PIP_Landmark.getY(),
                    ring_DIP_Landmark.getY(),
                    ring_TIP_Landmark.getY(),

                    pinky_MCP_Landmark.getY(),
                    pinky_PIP_Landmark.getY(),
                    pinky_DIP_Landmark.getY(),
                    pinky_TIP_Landmark.getY()
            };

            double[] allValuesFromLandmarks = new double[42];
            for (int i =0; i < 21; i++) {
                allValuesFromLandmarks[i] = allValuesX[i];
                allValuesFromLandmarks[21+i] = allValuesY[i];
            }

           double[] AllValXY = new double[allValuesFromLandmarks.length];
            for (int i =0; i < allValuesFromLandmarks.length; i++) {
                double d = (double)(allValuesFromLandmarks[i]);
                AllValXY[i] = d;
                //Log.i(TAG, i + " -----------------" + d);
            }
            return AllValXY;
        }
    }


    private double[] getNorm_01(double[] l)
    {
        double[] lineX = Arrays.copyOfRange(l, 0, 21);
        double[] lineY = Arrays.copyOfRange(l, 21, l.length);

        double minLX = lineX[getIndexOfMinVal(lineX)];
        double maxLX = lineX[getIndexOfMaxVal(lineX)];

        double minLY = lineY[getIndexOfMinVal(lineY)];
        double maxLY = lineY[getIndexOfMaxVal(lineY)];

        for (int i = 0; i < lineX.length; i++)
        {
            // przesunięcie do nowego 0
            lineX[i] = (lineX[i] - minLX);
            lineY[i] = (lineY[i] - minLY);

        }
        for (int i = 0; i < lineX.length; i++)
        {
            if(maxLX>0 && maxLY>0) {
                // przesunięcie do nowego 1
                lineX[i] = (lineX[i]/maxLX);
                lineY[i] = (lineY[i]/maxLY);
            }

        }
        // złożenie w 1:
        double[] endline =
                {
                        lineX[0] , lineY[0],
                        lineX[1] , lineY[1],
                        lineX[2] , lineY[2],
                        lineX[3] , lineY[3],
                        lineX[4] , lineY[4],
                        lineX[5] , lineY[5],
                        lineX[6] , lineY[6],
                        lineX[7] , lineY[7],
                        lineX[8] , lineY[8],
                        lineX[9] , lineY[9],
                        lineX[10] , lineY[10],
                        lineX[11] , lineY[11],
                        lineX[12] , lineY[12],
                        lineX[13] , lineY[13],
                        lineX[14] , lineY[14],
                        lineX[15] , lineY[15],
                        lineX[16] , lineY[16],
                        lineX[17] , lineY[17],
                        lineX[18] , lineY[18],
                        lineX[19] , lineY[19],
                        lineX[20] , lineY[20]
                };
        /*for (int i =0; i < endline.length; i++) {
            Log.i(TAG, i + " -----------------" + endline[i]);
        }*/
        return endline;
    }

    public int getIndexOfMaxVal( double[] arr)
    {
        if (arr == null || arr.length == 0) return -1;
        int idxMax = 0;
        for (int i=0;i<arr.length;i++)
        {
            if (arr[i] > arr[idxMax]) idxMax=i;
        }
        return idxMax;
    }

    public int getIndexOfMinVal( double[] arr)
    {
        if (arr == null || arr.length == 0) return -1;
        int idxMin = 0;
        for (int i=0;i<arr.length;i++)
        {
            if (arr[i] < arr[idxMin]) idxMin=i;
        }
        return idxMin;
    }
}